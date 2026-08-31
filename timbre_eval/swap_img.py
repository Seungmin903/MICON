# 보고서 HTML 안의 특정 alt 이미지의 base64를 새 PNG로 교체
import base64
import re
import sys

html_path, png_path, alt = sys.argv[1], sys.argv[2], sys.argv[3]
html = open(html_path, encoding="utf-8").read()
b64 = base64.b64encode(open(png_path, "rb").read()).decode()
pat = r'src="data:image/png;base64,[^"]+" alt="' + re.escape(alt) + '"'
new, n = re.subn(pat, f'src="data:image/png;base64,{b64}" alt="{alt}"', html)
open(html_path, "w", encoding="utf-8").write(new)
print("replaced:", n)
