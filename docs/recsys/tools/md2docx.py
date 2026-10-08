# -*- coding: utf-8 -*-
"""需求规格说明书 Markdown 转 Word（.docx）。

用法:
    python md2docx.py <输入.md> <输出.docx>

设计约定:
    - A4 竖版，正文区宽 15.8cm；章标题另起一页
    - 正文宋体 + Times New Roman 10.5pt，行距 1.5，首行缩进 2 字符
    - 标题微软雅黑加粗纯黑；表格列宽按内容分配并保证英文词不被断开
"""
import os
import re
import sys

from docx import Document
from docx.enum.style import WD_STYLE_TYPE
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

TITLE_FONT = '微软雅黑'
BODY_EA_FONT = '宋体'
BODY_ASCII_FONT = 'Times New Roman'
MONO_FONT = 'Consolas'

BLACK = RGBColor(0x00, 0x00, 0x00)
GRAY_DARK = RGBColor(0x40, 0x40, 0x40)
GRAY_MID = RGBColor(0x59, 0x59, 0x59)
GRAY_LIGHT = RGBColor(0x7F, 0x7F, 0x7F)

HEADER_SHADE = 'EEF3FB'
CONTENT_WIDTH_CM = 15.8
MAX_IMAGE_H_CM = 13.5  # 单张插图高度上限，超高的图按高度反推宽度，避免整页留白
CM_PER_PT = 0.03528

BODY_SIZE = Pt(12)
TABLE_SIZE = Pt(9)
TABLE_SIZE_WIDE = Pt(8.5)
CELL_MARGIN_CM = 0.25

HEAD_SIZES = {1: Pt(15), 2: Pt(14), 3: Pt(12), 4: Pt(11)}
HEAD_SPACE_BEFORE = {1: Pt(18), 2: Pt(12), 3: Pt(10), 4: Pt(8)}
HEAD_SPACE_AFTER = {1: Pt(10), 2: Pt(6), 3: Pt(6), 4: Pt(4)}

INLINE_RE = re.compile(r'(\*\*.+?\*\*|`[^`]+`)')


# ---------- 字体与样式 ----------

def _set_rfonts(element, ascii_font, ea_font):
    rpr = element.get_or_add_rPr()
    rfonts = rpr.find(qn('w:rFonts'))
    if rfonts is None:
        rfonts = OxmlElement('w:rFonts')
        rpr.insert(0, rfonts)
    rfonts.set(qn('w:ascii'), ascii_font)
    rfonts.set(qn('w:hAnsi'), ascii_font)
    rfonts.set(qn('w:eastAsia'), ea_font)
    rfonts.set(qn('w:cs'), ascii_font)


def font_run(run, size=BODY_SIZE, bold=None, italic=None, color=None,
             ascii_font=BODY_ASCII_FONT, ea_font=BODY_EA_FONT):
    run.font.size = size
    if bold is not None:
        run.font.bold = bold
    if italic is not None:
        run.font.italic = italic
    if color is not None:
        run.font.color.rgb = color
    _set_rfonts(run._element, ascii_font, ea_font)


def font_style(style, size=None, bold=None, color=None,
               ascii_font=BODY_ASCII_FONT, ea_font=BODY_EA_FONT, italic=None):
    if size is not None:
        style.font.size = size
    if bold is not None:
        style.font.bold = bold
    if italic is not None:
        style.font.italic = italic
    if color is not None:
        style.font.color.rgb = color
    _set_rfonts(style.element, ascii_font, ea_font)


def strip_para_border(style):
    ppr = style.element.find(qn('w:pPr'))
    if ppr is None:
        return
    for el in ppr.findall(qn('w:pBdr')):
        ppr.remove(el)


def setup_styles(doc):
    normal = doc.styles['Normal']
    font_style(normal, size=BODY_SIZE, bold=False, color=BLACK)
    normal.paragraph_format.line_spacing = 1.5
    normal.paragraph_format.space_after = Pt(6)
    normal.paragraph_format.space_before = Pt(0)

    title = doc.styles['Title']
    font_style(title, size=Pt(20), bold=True, color=BLACK,
               ascii_font=TITLE_FONT, ea_font=TITLE_FONT)
    strip_para_border(title)
    title.paragraph_format.space_before = Pt(0)
    title.paragraph_format.space_after = Pt(2)

    for lvl in (1, 2, 3, 4):
        st = doc.styles['Heading %d' % lvl]
        font_style(st, size=HEAD_SIZES[lvl], bold=True, italic=False, color=BLACK,
                   ascii_font=TITLE_FONT, ea_font=TITLE_FONT)
        st.paragraph_format.space_before = HEAD_SPACE_BEFORE[lvl]
        st.paragraph_format.space_after = HEAD_SPACE_AFTER[lvl]
        st.paragraph_format.keep_with_next = True
        st.paragraph_format.line_spacing = 1.25
        strip_para_border(st)

    for name in ('List Bullet', 'List Bullet 2'):
        try:
            font_style(doc.styles[name], size=BODY_SIZE, bold=False, color=BLACK)
            doc.styles[name].paragraph_format.line_spacing = 1.4
            doc.styles[name].paragraph_format.space_after = Pt(3)
        except KeyError:
            pass

    # 目录条目样式：默认继承 Normal 的 1.5 行距会过于松散
    for name, size in (('TOC 1', Pt(10.5)), ('TOC 2', Pt(10)), ('TOC 3', Pt(10))):
        try:
            st = doc.styles[name]
        except KeyError:
            st = doc.styles.add_style(name, WD_STYLE_TYPE.PARAGRAPH)
        font_style(st, size=size, bold=False, color=BLACK)
        st.paragraph_format.line_spacing = 1.0
        st.paragraph_format.space_before = Pt(0)
        st.paragraph_format.space_after = Pt(1)

    # 封面区小标题：外观与 Heading 2 一致，但不进入目录
    try:
        cover = doc.styles['CoverHeading']
    except KeyError:
        cover = doc.styles.add_style('CoverHeading', WD_STYLE_TYPE.PARAGRAPH)
    font_style(cover, size=Pt(13.5), bold=True, color=BLACK,
               ascii_font=TITLE_FONT, ea_font=TITLE_FONT)
    cover.paragraph_format.space_before = Pt(14)
    cover.paragraph_format.space_after = Pt(6)
    cover.paragraph_format.line_spacing = 1.25


def setup_page(doc):
    sec = doc.sections[0]
    sec.page_width = Cm(21.0)
    sec.page_height = Cm(29.7)
    sec.top_margin = Cm(2.4)
    sec.bottom_margin = Cm(2.4)
    sec.left_margin = Cm(2.6)
    sec.right_margin = Cm(2.6)
    sec.header_distance = Cm(1.4)
    sec.footer_distance = Cm(1.4)


# ---------- 行内、缩进、域 ----------

def add_inline(paragraph, text, size=BODY_SIZE, bold=False, color=None,
               ascii_font=BODY_ASCII_FONT, ea_font=BODY_EA_FONT, italic=None):
    for token in INLINE_RE.split(text):
        if not token:
            continue
        if token.startswith('**') and token.endswith('**') and len(token) > 4:
            font_run(paragraph.add_run(token[2:-2]), size=size, bold=True,
                     color=color, ascii_font=ascii_font, ea_font=ea_font, italic=italic)
        elif token.startswith('`') and token.endswith('`') and len(token) > 2:
            font_run(paragraph.add_run(token[1:-1]), size=Pt(size.pt - 0.5), bold=bold,
                     color=color, ascii_font=MONO_FONT, ea_font=MONO_FONT, italic=italic)
        else:
            font_run(paragraph.add_run(token), size=size, bold=bold,
                     color=color, ascii_font=ascii_font, ea_font=ea_font, italic=italic)
    return paragraph


def indent_two_chars(paragraph):
    """首行缩进 2 个字符（东亚版式，随字号自适应）。"""
    pf = paragraph.paragraph_format
    pf.first_line_indent = Pt(BODY_SIZE.pt * 2)
    ppr = paragraph._p.get_or_add_pPr()
    ind = ppr.find(qn('w:ind'))
    if ind is not None:
        ind.set(qn('w:firstLineChars'), '200')


def add_field(paragraph, instr, size=Pt(8), color=GRAY_LIGHT):
    run = paragraph.add_run()
    font_run(run, size=size, color=color)
    begin = OxmlElement('w:fldChar')
    begin.set(qn('w:fldCharType'), 'begin')
    instr_el = OxmlElement('w:instrText')
    instr_el.set(qn('xml:space'), 'preserve')
    instr_el.text = instr
    end = OxmlElement('w:fldChar')
    end.set(qn('w:fldCharType'), 'end')
    run._r.append(begin)
    run._r.append(instr_el)
    run._r.append(end)


def add_toc(doc):
    head = doc.add_paragraph()
    head.paragraph_format.space_before = Pt(14)
    head.paragraph_format.space_after = Pt(6)
    add_inline(head, '目录', size=Pt(15), bold=True, color=BLACK,
               ascii_font=TITLE_FONT, ea_font=TITLE_FONT)

    para = doc.add_paragraph()
    para.paragraph_format.space_after = Pt(10)
    run = para.add_run()
    font_run(run, size=BODY_SIZE, color=GRAY_MID)
    fld = OxmlElement('w:fldSimple')
    fld.set(qn('w:instr'), 'TOC \\o "1-2" \\h \\z \\u')
    inner = OxmlElement('w:r')
    txt = OxmlElement('w:t')
    txt.text = '（在 Word 中按 F9 更新目录）'
    inner.append(txt)
    fld.append(inner)
    para._p.append(fld)


def setup_header_footer(doc, header_text):
    sec = doc.sections[0]
    hp = sec.header.paragraphs[0]
    hp.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    hp.paragraph_format.space_after = Pt(2)
    add_inline(hp, header_text, size=Pt(8), color=GRAY_LIGHT)

    fp = sec.footer.paragraphs[0]
    fp.alignment = WD_ALIGN_PARAGRAPH.CENTER
    font_run(fp.add_run('第 '), size=Pt(8), color=GRAY_LIGHT)
    add_field(fp, 'PAGE')
    font_run(fp.add_run(' 页  共 '), size=Pt(8), color=GRAY_LIGHT)
    add_field(fp, 'NUMPAGES')
    font_run(fp.add_run(' 页'), size=Pt(8), color=GRAY_LIGHT)


# ---------- 表格 ----------

def shade_cell(cell, hex_fill):
    tcpr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement('w:shd')
    shd.set(qn('w:val'), 'clear')
    shd.set(qn('w:color'), 'auto')
    shd.set(qn('w:fill'), hex_fill)
    tcpr.append(shd)


def set_cell_margins(table, top=40, start=70, bottom=40, end=70):
    mar = OxmlElement('w:tblCellMar')
    for tag, val in (('top', top), ('start', start), ('bottom', bottom), ('end', end)):
        el = OxmlElement('w:' + tag)
        el.set(qn('w:w'), str(val))
        el.set(qn('w:type'), 'dxa')
        mar.append(el)
    table._tbl.tblPr.append(mar)


def set_repeat_header(row):
    trpr = row._tr.get_or_add_trPr()
    el = OxmlElement('w:tblHeader')
    el.set(qn('w:val'), 'true')
    trpr.append(el)


def disp_width(text):
    return sum(2 if ord(ch) > 0x2E7F else 1 for ch in text)


def longest_token(text):
    """最长不可断片段宽度：中文可任意断行，英文/数字串不可断。"""
    best = 0
    cur = 0
    for ch in text:
        if ord(ch) > 0x2E7F:
            cur = 0
        else:
            cur += 1
        if cur > best:
            best = cur
    return best


def is_separator_row(row_text):
    return re.fullmatch(r'\|[\s\|:\-]+\|', row_text) is not None


def compute_widths(rows, ncols, size):
    char_cm = size.pt * 0.5 * CM_PER_PT
    weights = []
    mins = []
    for ci in range(ncols):
        longest = max(max(disp_width(r[ci]) for r in rows), 2)
        token = max(longest_token(r[ci]) for r in rows)
        weights.append(longest)
        floor = max(token * char_cm + CELL_MARGIN_CM + 0.15, 1.05)
        mins.append(min(floor, CONTENT_WIDTH_CM * 0.42))

    if sum(mins) >= CONTENT_WIDTH_CM:
        scale = CONTENT_WIDTH_CM / sum(mins)
        return [m * scale for m in mins]

    free = CONTENT_WIDTH_CM - sum(mins)
    wants = []
    for ci in range(ncols):
        desired = weights[ci] * char_cm + 0.5
        wants.append(max(desired - mins[ci], 0.12))
    total_wants = sum(wants)
    return [mins[ci] + free * wants[ci] / total_wants for ci in range(ncols)]


def render_table(doc, block):
    rows = []
    for raw in block:
        if is_separator_row(raw):
            continue
        rows.append([c.strip() for c in raw.strip().strip('|').split('|')])
    if not rows:
        return

    ncols = max(len(r) for r in rows)
    for r in rows:
        while len(r) < ncols:
            r.append('')

    has_header = ncols >= 3
    size = TABLE_SIZE_WIDE if ncols >= 5 else TABLE_SIZE
    widths = compute_widths(rows, ncols, size)

    table = doc.add_table(rows=len(rows), cols=ncols)
    table.style = 'Table Grid'
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    set_cell_margins(table)
    for ci, width in enumerate(widths):
        table.columns[ci].width = Cm(width)

    for ri, row in enumerate(rows):
        for ci, text in enumerate(row):
            cell = table.cell(ri, ci)
            cell.width = Cm(widths[ci])
            para = cell.paragraphs[0]
            pf = para.paragraph_format
            pf.space_before = Pt(1.5)
            pf.space_after = Pt(1.5)
            pf.line_spacing = 1.18
            head_row = (ri == 0 and has_header)
            if head_row:
                para.alignment = WD_ALIGN_PARAGRAPH.CENTER
            add_inline(para, text, size=size,
                       bold=(head_row or (ncols == 2 and ci == 0)))
            if head_row:
                shade_cell(cell, HEADER_SHADE)

    if has_header:
        set_repeat_header(table.rows[0])

    spacer = doc.add_paragraph()
    spacer.paragraph_format.space_after = Pt(0)
    spacer.paragraph_format.space_before = Pt(0)
    spacer.paragraph_format.line_spacing = 1.0
    font_run(spacer.add_run(''), size=Pt(6))


# ---------- 块级 ----------

def add_heading(doc, text, level, page_break=False):
    para = doc.add_paragraph(style='Heading %d' % level)
    if page_break:
        para.paragraph_format.page_break_before = True
    add_inline(para, text, size=HEAD_SIZES[level], bold=True, color=BLACK,
               ascii_font=TITLE_FONT, ea_font=TITLE_FONT)
    return para


def add_image(doc, caption, path, base_dir):
    target = path if os.path.isabs(path) else os.path.join(base_dir, path)
    if not os.path.exists(target):
        para = doc.add_paragraph()
        add_inline(para, '[缺失图片] ' + caption, size=Pt(9), color=GRAY_MID)
        return
    para = doc.add_paragraph()
    para.alignment = WD_ALIGN_PARAGRAPH.CENTER
    para.paragraph_format.space_before = Pt(8)
    para.paragraph_format.space_after = Pt(2)
    width_cm = CONTENT_WIDTH_CM
    try:
        from docx.image.image import Image as _DocxImage
        _info = _DocxImage.from_file(target)
        w_px = float(_info.px_width)
        h_px = float(_info.px_height)
        if w_px > 0 and h_px / w_px * width_cm > MAX_IMAGE_H_CM:
            width_cm = MAX_IMAGE_H_CM * w_px / h_px
    except Exception:
        pass
    para.add_run().add_picture(target, width=Cm(width_cm))
    if caption:
        cap = doc.add_paragraph()
        cap.alignment = WD_ALIGN_PARAGRAPH.CENTER
        cap.paragraph_format.space_after = Pt(12)
        add_inline(cap, caption, size=Pt(9), color=GRAY_MID,
                   ascii_font=TITLE_FONT, ea_font=TITLE_FONT)


def render_code_block(doc, block):
    """围栏代码块（JSON 报文等）：等宽字体逐行输出，保留原始缩进与换行。"""
    if not block:
        return
    last = len(block) - 1
    for idx, text in enumerate(block):
        para = doc.add_paragraph()
        pf = para.paragraph_format
        pf.left_indent = Cm(0.6)
        pf.line_spacing = 1.05
        pf.space_before = Pt(4) if idx == 0 else Pt(0)
        pf.space_after = Pt(8) if idx == last else Pt(0)
        if text.strip():
            font_run(para.add_run(text.rstrip()), size=Pt(9.5),
                     ascii_font=MONO_FONT, ea_font=MONO_FONT)


def flush_paragraph(doc, buf):
    if not buf:
        return
    text = ''
    for idx, piece in enumerate(buf):
        if idx and text and re.search(r'[0-9A-Za-z]$', text) and re.match(r'^[0-9A-Za-z]', piece):
            text += ' '
        text += piece
    del buf[:]
    if not text:
        return

    stripped = text.strip()
    only_bold = (stripped.startswith('**') and stripped.endswith('**')
                 and stripped.count('**') == 2)
    para = doc.add_paragraph()
    if only_bold:
        # 纯加粗小标题：不缩进，加一点上间距
        para.paragraph_format.space_before = Pt(4)
        add_inline(para, stripped[2:-2], size=Pt(11), bold=True, color=BLACK,
                   ascii_font=TITLE_FONT, ea_font=TITLE_FONT)
    else:
        indent_two_chars(para)
        add_inline(para, text)


# ---------- 主流程 ----------

def convert(md_path, out_path,
            header_text='基于实时特征的个性化商品推荐系统 · 需求规格说明书'):
    with open(md_path, encoding='utf-8-sig') as fh:
        lines = fh.read().splitlines()

    base_dir = os.path.dirname(os.path.abspath(md_path))
    doc = Document()
    setup_styles(doc)
    setup_page(doc)
    setup_header_footer(doc, header_text)

    subtitle_pending = True
    toc_done = False
    chapter_seen = False
    buf = []
    i = 0
    while i < len(lines):
        raw = lines[i]
        s = raw.strip()

        if s.startswith('|'):
            flush_paragraph(doc, buf)
            block = []
            while i < len(lines) and lines[i].strip().startswith('|'):
                block.append(lines[i].strip())
                i += 1
            render_table(doc, block)
            continue

        if s.startswith('```'):
            flush_paragraph(doc, buf)
            i += 1
            code_lines = []
            while i < len(lines) and not lines[i].strip().startswith('```'):
                code_lines.append(lines[i])
                i += 1
            i += 1
            render_code_block(doc, code_lines)
            continue

        if re.fullmatch(r'-{3,}', s):
            flush_paragraph(doc, buf)
            i += 1
            continue

        m = re.fullmatch(r'!\[(.*?)\]\((.*?)\)', s)
        if m:
            flush_paragraph(doc, buf)
            add_image(doc, m.group(1), m.group(2), base_dir)
            i += 1
            continue

        m = re.match(r'^(#{1,6})\s+(.*)$', s)
        if m:
            flush_paragraph(doc, buf)
            level = len(m.group(1))
            text = m.group(2).strip()
            if level == 1:
                para = doc.add_paragraph(style='Title')
                para.alignment = WD_ALIGN_PARAGRAPH.CENTER
                add_inline(para, text, size=Pt(20), bold=True, color=BLACK,
                           ascii_font=TITLE_FONT, ea_font=TITLE_FONT)
            elif level == 2 and subtitle_pending:
                subtitle_pending = False
                para = doc.add_paragraph()
                para.alignment = WD_ALIGN_PARAGRAPH.CENTER
                para.paragraph_format.space_before = Pt(0)
                para.paragraph_format.space_after = Pt(16)
                add_inline(para, text, size=Pt(14), bold=False, color=GRAY_DARK,
                           ascii_font=TITLE_FONT, ea_font=TITLE_FONT)
            else:
                if level == 2:
                    if not toc_done:
                        add_toc(doc)
                        toc_done = True
                    chapter_seen = True
                    add_heading(doc, text, 1, page_break=True)
                elif chapter_seen:
                    add_heading(doc, text, min(level - 1, 4))
                else:
                    para = doc.add_paragraph(style='CoverHeading')
                    add_inline(para, text, size=Pt(13.5), bold=True, color=BLACK,
                               ascii_font=TITLE_FONT, ea_font=TITLE_FONT)
            i += 1
            continue

        m = re.match(r'^(\s*)([-*+]|\d+[.)])\s+(.*)$', raw)
        if m:
            flush_paragraph(doc, buf)
            indent = len(m.group(1))
            marker = m.group(2)
            text = m.group(3)
            if marker[0].isdigit():
                para = doc.add_paragraph()
                para.paragraph_format.left_indent = Cm(1.0)
                para.paragraph_format.first_line_indent = Cm(-0.6)
                para.paragraph_format.space_after = Pt(3)
                para.paragraph_format.line_spacing = 1.4
                add_inline(para, marker.rstrip(').') + '. ' + text)
            else:
                style = 'List Bullet 2' if indent >= 2 else 'List Bullet'
                add_inline(doc.add_paragraph(style=style), text)
            i += 1
            continue

        if s.startswith('>'):
            flush_paragraph(doc, buf)
            para = doc.add_paragraph()
            pf = para.paragraph_format
            pf.left_indent = Cm(0.8)
            pf.space_before = Pt(4)
            pf.space_after = Pt(8)
            add_inline(para, s.lstrip('>').strip(), size=Pt(9.5), color=GRAY_MID, italic=True)
            i += 1
            continue

        if not s:
            # 空行是 Markdown 的段落分隔符：先落盘上一段，
            # 否则连续多个段落会被拼接成一整段。
            flush_paragraph(doc, buf)
            i += 1
            continue

        buf.append(s)
        i += 1

    flush_paragraph(doc, buf)

    out_dir = os.path.dirname(os.path.abspath(out_path))
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    doc.save(out_path)
    return out_path


def main():
    if len(sys.argv) < 3:
        print('用法: python md2docx.py <输入.md> <输出.docx> [页眉文本]')
        return 2
    header = sys.argv[3] if len(sys.argv) > 3 else \
        '基于实时特征的个性化商品推荐系统 · 需求规格说明书'
    print('已生成: ' + convert(sys.argv[1], sys.argv[2], header))
    return 0


if __name__ == '__main__':
    sys.exit(main())
