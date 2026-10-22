"""Assert physical PDF table geometry independently of the HTML renderer."""
import json
import logging
import sys
import pdfplumber

logging.getLogger('pdfminer').setLevel(logging.ERROR)
with pdfplumber.open(sys.argv[1]) as document:
    page = document.pages[0]
    tables = page.find_tables()
    assert len(tables) == 1, 'Expected one real table'
    cells = tables[0].rows[0].cells
    assert len(cells) == 2, 'Expected two table columns'
    widths = [cell[2] - cell[0] for cell in cells]
    ratio = widths[0] / widths[1]
    expected_ratio = float(sys.argv[2])
    assert abs(ratio - expected_ratio) < 0.05, f'PDF width ratio {ratio} differs from designer {expected_ratio}'
    money = [word for word in page.extract_words() if word['text'].startswith('¥') and word['top'] >= tables[0].bbox[1] and word['bottom'] <= tables[0].bbox[3]]
    assert {word['text'] for word in money} == {'¥128,000.00', '¥132,000.50', '¥286,000.00'}, 'Exact formatted amounts required'
    assert max(word['x1'] for word in money) - min(word['x1'] for word in money) < 1, 'Money is not right-aligned'
    right = cells[1][2]
    assert all(0 < right - word['x1'] < 10 for word in money), 'Money must sit at the right cell edge'
    print(json.dumps({'pages': len(document.pages), 'columnWidthsPt': widths, 'ratio': ratio, 'moneyRightEdgesPt': [word['x1'] for word in money], 'amounts': [word['text'] for word in money]}))
