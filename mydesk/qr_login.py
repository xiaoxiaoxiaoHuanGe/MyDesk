"""Local QR rendering: login secrets are never sent to an external QR service."""
import base64
import qrcode
from qrcode.image.svg import SvgPathImage


def browser_label(agent):
    system=next((label for token,label in [('Windows','Windows'),('Macintosh','macOS'),('Android','Android'),('iPhone','iPhone'),('Linux','Linux')] if token in agent),'电脑')
    browser=next((label for token,label in [('Edg/','Edge'),('Firefox/','Firefox'),('Chrome/','Chrome'),('Safari/','Safari')] if token in agent),'浏览器')
    return system+' · '+browser


def qr_image(uri):
    code=qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M,box_size=8,border=4)
    code.add_data(uri)
    code.make(fit=True)
    svg=code.make_image(image_factory=SvgPathImage).to_string()
    return 'data:image/svg+xml;base64,'+base64.b64encode(svg).decode('ascii')
