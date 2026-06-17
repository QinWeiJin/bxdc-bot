"""FTP server for local bxdc-bot-1 development."""
import os
from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer

FTP_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ftp_root")
os.makedirs(FTP_DIR, exist_ok=True)
os.makedirs(os.path.join(FTP_DIR, "files"), exist_ok=True)

authorizer = DummyAuthorizer()
authorizer.add_user("ftpuser", "ftpuser", FTP_DIR, perm="elradfmwMT")

handler = FTPHandler
handler.authorizer = authorizer
handler.passive_ports = range(30000, 30010)
handler.masquerade_address = "127.0.0.1"

server = FTPServer(("127.0.0.1", 2121), handler)
print(f"FTP server started on 127.0.0.1:2121, root={FTP_DIR}")
server.serve_forever()
