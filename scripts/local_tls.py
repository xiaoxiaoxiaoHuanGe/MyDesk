"""Generate a project-local CA and LAN server certificate, without changing device trust stores."""
import ipaddress
from datetime import datetime,timedelta,timezone
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID,ExtendedKeyUsageOID


def prepare_tls(directory,lan_ip=None):
    directory.mkdir(parents=True,exist_ok=True)
    now=datetime.now(timezone.utc)
    ca_path=directory/'mydesk-local-ca.crt'
    key_path=directory/'ca-key.pem'
    pem=lambda key:key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption())
    if not ca_path.exists():
        key=ec.generate_private_key(ec.SECP256R1())
        name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'MyDesk Local Test CA')])
        cert=(x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
              .serial_number(x509.random_serial_number()).not_valid_before(now-timedelta(minutes=5)).not_valid_after(now+timedelta(days=3650))
              .add_extension(x509.BasicConstraints(ca=True,path_length=0),critical=True)
              .add_extension(x509.KeyUsage(False,False,False,False,False,True,True,False,False),critical=True)
              .sign(key,hashes.SHA256()))
        key_path.write_bytes(pem(key));ca_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    key=serialization.load_pem_private_key(key_path.read_bytes(),None)
    ca=x509.load_pem_x509_certificate(ca_path.read_bytes())
    server_path=directory/'server.pem'
    hosts=[x509.DNSName('localhost'),x509.IPAddress(ipaddress.ip_address('127.0.0.1'))]
    if lan_ip:
        hosts.append(x509.IPAddress(ipaddress.ip_address(lan_ip)))
    if server_path.exists():
        existing=x509.load_pem_x509_certificate(server_path.read_bytes())
        san=existing.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
        if all(host in san for host in hosts) and existing.not_valid_after_utc>now+timedelta(days=1):
            return
    server_key=ec.generate_private_key(ec.SECP256R1())
    cert=(x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'MyDesk Local')]))
          .issuer_name(ca.subject).public_key(server_key.public_key()).serial_number(x509.random_serial_number())
          .not_valid_before(now-timedelta(minutes=5)).not_valid_after(now+timedelta(days=365))
          .add_extension(x509.SubjectAlternativeName(hosts),critical=False)
          .add_extension(x509.BasicConstraints(ca=False,path_length=None),critical=True)
          .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]),critical=False)
          .sign(key,hashes.SHA256()))
    (directory/'server-key.pem').write_bytes(pem(server_key))
    server_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
