"""Validate and parse MyDesk v1 task results from workflow output."""
import json
import math

PREFIX='MYDESK_RESULT='
STATES={'success','failed','warning','unknown'}


def validate_result(value):
    if not isinstance(value,dict) or value.keys()-{'version','status','message','metrics'} or value.get('version')!=1 or type(value.get('version')) is not int or value.get('status') not in STATES:
        raise ValueError('结果版本或状态无效')
    message=value.get('message')
    if not isinstance(message,str) or not message.strip() or len(message)>300 or any(ord(c)<32 for c in message):
        raise ValueError('结果说明需要为 1–300 个字符的单行文字')
    metrics=value.get('metrics',[])
    if not isinstance(metrics,list) or len(metrics)>12:
        raise ValueError('最多提供 12 个结果指标')
    for metric in metrics:
        if not isinstance(metric,dict) or metric.keys()-{'label','value','unit'}:
            raise ValueError('结果指标格式无效')
        for key,limit in [('label',24),('unit',12)]:
            text=metric.get(key,'')
            if not isinstance(text,str) or len(text)>limit or any(ord(c)<32 for c in text) or (key=='label' and not text.strip()):
                raise ValueError('指标名称或单位无效')
        number=metric.get('value')
        if type(number) not in (int,float,str) or (isinstance(number,str) and (len(number)>80 or any(ord(c)<32 for c in number))) or (type(number) in (int,float) and (not math.isfinite(number) or abs(number)>1e15)):
            raise ValueError('指标值无效')
    return {'version':1,'status':value['status'],'message':message.strip(),'metrics':metrics}


def parse_result(lines):
    matches=[line[len(PREFIX):] for line in lines if line.startswith(PREFIX)]
    if not matches:
        return None
    if len(matches[-1])>8192:
        raise ValueError('标准结果过大')
    try:
        return validate_result(json.loads(matches[-1]))
    except (ValueError,TypeError,OverflowError) as exc:
        raise ValueError('标准结果格式无效') from exc


def result_message(result):
    parts=[result['message']]
    for metric in result['metrics']:
        parts.append(' '.join(str(value) for value in [metric['label'],metric['value'],metric.get('unit','')] if str(value)))
    return ' · '.join(parts)


