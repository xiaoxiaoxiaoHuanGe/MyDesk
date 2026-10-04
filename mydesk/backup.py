"""Portable encrypted configuration backups; never export accounts or sessions."""
import base64
import copy
import hashlib
import json
import os
import secrets
import time
from datetime import datetime, timezone
from pathlib import Path

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from .config import DEFAULTS, mail_accounts, server_sources

FORMAT = 'mydesk-config-backup'
ITERATIONS = 600000
MAX_FILE_BYTES = 128 * 1024
MAX_PLAIN_BYTES = 64 * 1024
FIELDS = {'timezone', 'history_days', 'network', 'expected_tasks', 'github',
          'github_tasks', 'gmail_accounts', 'server_sources'}
COLLECTIONS = ('github_tasks', 'gmail_accounts', 'server_sources')


def encoded(value):
    return base64.b64encode(value).decode('ascii')


def password_key(password, salt):
    if not isinstance(password, str) or not 12 <= len(password) <= 256:
        raise ValueError('备份密码需要 12–256 个字符')
    return hashlib.pbkdf2_hmac('sha256', password.encode(), salt, ITERATIONS, 32)


def selected(value):
    result = {key: copy.deepcopy(value[key]) for key in FIELDS if key in value}
    result['gmail_accounts'] = copy.deepcopy(mail_accounts(value))
    result['server_sources'] = copy.deepcopy(server_sources(value))
    return result


def revision(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def identity(kind, item):
    if kind == 'gmail_accounts':
        return item['username'].lower()
    if kind == 'server_sources':
        return item['provider'], item['url'].rstrip('/').lower()
    return item['owner'].lower(), item['repo'].lower(), item['workflow'], item['ref']


class Backups:
    def __init__(self, settings, directory):
        self.settings = settings
        self.directory = Path(directory)
        self.pending = {}

    def export(self, password, appearance='system', source_server=''):
        if appearance not in ('system', 'light', 'dark') or not isinstance(source_server, str) or len(source_server) > 2048:
            raise ValueError('备份信息格式无效')
        payload = {'format': FORMAT, 'version': 1, 'created_at': datetime.now(timezone.utc).isoformat(),
                   'appearance': appearance, 'source_server': source_server, 'settings': selected(self.settings.value)}
        raw = json.dumps(payload, ensure_ascii=False).encode()
        if len(raw) > MAX_PLAIN_BYTES:
            raise ValueError('配置过大，无法导出此格式的备份')
        salt, nonce = secrets.token_bytes(16), secrets.token_bytes(12)
        encrypted = AESGCM(password_key(password, salt)).encrypt(nonce, raw, FORMAT.encode())
        return {'format': FORMAT, 'version': 1, 'iterations': ITERATIONS, 'salt': encoded(salt),
                'nonce': encoded(nonce), 'ciphertext': encoded(encrypted)}

    def decrypt(self, blob, password):
        if not isinstance(blob, dict) or set(blob) != {'format', 'version', 'iterations', 'salt', 'nonce', 'ciphertext'}:
            raise ValueError('不是有效的 MyDesk 加密备份')
        if blob['format'] != FORMAT or type(blob['version']) is not int or blob['version'] != 1 or blob['iterations'] != ITERATIONS:
            raise ValueError('不支持此备份版本')
        try:
            if any(not isinstance(blob[key], str) or len(blob[key]) > MAX_FILE_BYTES for key in ('salt', 'nonce', 'ciphertext')):
                raise ValueError()
            salt, nonce, data = (base64.b64decode(blob[key], validate=True) for key in ('salt', 'nonce', 'ciphertext'))
            if len(salt) != 16 or len(nonce) != 12 or not 16 <= len(data) <= MAX_PLAIN_BYTES + 16:
                raise ValueError()
            raw = AESGCM(password_key(password, salt)).decrypt(nonce, data, FORMAT.encode())
            payload = json.loads(raw)
        except (ValueError, TypeError, InvalidTag, UnicodeError):
            raise ValueError('备份密码错误，或文件已损坏') from None
        if not isinstance(payload, dict) or set(payload) != {'format', 'version', 'created_at', 'source_server', 'appearance', 'settings'}:
            raise ValueError('备份内容格式无效')
        if payload['format'] != FORMAT or payload['version'] != 1 or payload['appearance'] not in ('system', 'light', 'dark'):
            raise ValueError('备份内容格式无效')
        if any(not isinstance(payload[key], str) or len(payload[key]) > limit for key, limit in [('created_at', 80), ('source_server', 2048)]):
            raise ValueError('备份内容格式无效')
        incoming = payload['settings']
        if not isinstance(incoming, dict) or incoming.keys() - FIELDS:
            raise ValueError('备份包含不支持的配置项')
        return payload

    def preview(self, blob, password, mode, session, current_appearance='system'):
        if mode not in ('merge', 'replace'):
            raise ValueError('恢复方式无效')
        payload = self.decrypt(blob, password)
        incoming = payload['settings']
        current = copy.deepcopy(self.settings.value)
        # Validate imported credentials independently of existing credentials.
        empty = {**copy.deepcopy(DEFAULTS), 'gmail_accounts': {}, 'server_sources': {}}
        imported = selected(self.settings.prepare(incoming, base=empty))
        changes, counts = [], {}
        if mode == 'replace':
            base = {**current, **empty}
            for key in FIELDS:
                base.pop(key, None)
            base.update(empty)
        else:
            base = current
        merged = copy.deepcopy(imported)
        for kind in COLLECTIONS:
            existing = selected(current).get(kind, {})
            entries = {} if mode == 'replace' else copy.deepcopy(existing)
            counts[kind] = {'added': 0, 'updated': 0, 'removed': 0}
            touched = set()
            for key, item in imported.get(kind, {}).items():
                match = next((old_key for old_key, old in existing.items() if identity(kind, old) == identity(kind, item)), None)
                target = match or key
                if target in existing and match is None:
                    target = secrets.token_hex(12)
                entries[target] = copy.deepcopy(item)
                touched.add(match)
                action = 'updated' if match else 'added'
                counts[kind][action] += 1
                changes.append({'kind': kind, 'name': item['name'], 'action': action})
            if mode == 'replace':
                for key, item in existing.items():
                    if key not in touched:
                        counts[kind]['removed'] += 1
                        changes.append({'kind': kind, 'name': item['name'], 'action': 'removed'})
            merged[kind] = entries
        candidate = self.settings.prepare(merged, base=base)
        now = time.monotonic()
        self.pending = {key: value for key, value in self.pending.items() if value['expires'] > now}
        # Keep one outstanding preview per session and bound global memory.
        self.pending = {key: value for key, value in self.pending.items() if value['session'] != session}
        if len(self.pending) >= 16:
            self.pending.pop(next(iter(self.pending)))
        preview_id = secrets.token_urlsafe(32)
        self.pending[preview_id] = {'session': session, 'expires': now + 600, 'revision': revision(current),
            'candidate': candidate, 'mode': mode, 'appearance': payload['appearance'],
            'rollback': self.export(password,current_appearance)}
        return {'preview_id': preview_id, 'mode': mode, 'created_at': payload['created_at'],
                'source_server': payload['source_server'], 'appearance': payload['appearance'],
                'counts': counts, 'changes': changes, 'steps_configured': bool(candidate.get('github')),
                'network_nodes': len(candidate.get('network', {}).get('nodes', [])),
                'timezone':candidate['timezone'],'history_days':candidate['history_days'],
                'message': '将恢复备份中的通用设置和外观；登录账号与提醒历史保持不变'}

    def plan(self, preview_id, session, confirmed):
        plan = self.pending.get(preview_id) if isinstance(preview_id, str) else None
        if not plan or plan['session'] != session or plan['expires'] <= time.monotonic():
            raise ValueError('恢复预览已失效，请重新选择备份')
        if plan['mode'] == 'replace' and confirmed is not True:
            raise ValueError('请先确认全部替换配置')
        if revision(self.settings.value) != plan['revision']:
            raise ValueError('配置已发生变化，请重新预览后恢复')
        return plan

    def apply(self, preview_id, session, confirmed):
        plan = self.plan(preview_id, session, confirmed)
        directory = self.directory / 'config-backups'
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        file = directory / ('restore-' + secrets.token_hex(16) + '.mydesk')
        with file.open('x', encoding='utf-8') as stream:
            try:
                file.chmod(0o600)
            except PermissionError:
                pass
            json.dump(plan['rollback'], stream)
            stream.flush()
            os.fsync(stream.fileno())
        self.settings.write(plan['candidate'])
        self.pending.pop(preview_id, None)
        # Bound encrypted rollback storage without making a completed restore fail.
        try:
            files=sorted(directory.glob('restore-*.mydesk'), key=lambda p:p.stat().st_mtime_ns, reverse=True)
            for old in files[10:]:old.unlink()
        except OSError:
            pass
        return {'restored': True, 'appearance': plan['appearance'], 'rollback_file': file.name,
                'message': '配置已恢复，原配置已加密保留'}
