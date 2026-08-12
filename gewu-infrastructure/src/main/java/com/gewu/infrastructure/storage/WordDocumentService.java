package com.gewu.infrastructure.storage;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word 文档生成服务 - 将 Markdown 文本转换为 Word(.docx) 文档。
 * <p>
 * 支持的 Markdown 元素：
 * <ul>
 *   <li>标题（# / ## / ### / #### / ##### / ######）</li>
 *   <li>段落（普通文本）</li>
 *   <li>代码块（```language ... ```）</li>
 *   <li>无序列表（- / * 开头）</li>
 *   <li>有序列表（1. 开头）</li>
 *   <li>表格（| ... |）</li>
 *   <li>粗体（**text**）</li>
 *   <li>引用块（> text）</li>
 *   <li>水平线（---）</li>
 * </ul>
 * 生成 .docx 字节数组，由 {@link com.gewu.application.wenshi.output.FileOutputService} 上传到 MinIO。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
public class WordDocumentService {

    /** 粗体标记正则 */
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");
    /** 代码块标记 */
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```(\\w*)\\n([\\s\\S]*?)```");
    /** 表格行标记 */
    private static final Pattern TABLE_ROW_PATTERN = Pattern.compile("^\\|(.+)\\|$", Pattern.MULTILINE);

    /**
     * 将 Markdown 文本转换为 Word 文档字节数组。
     *
     * @param markdown Markdown 文本
     * @return .docx 文件字节数组
     * @since 1.0.0
     */
    public byte[] markdownToWord(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            markdown = "";
        }

        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            // 先提取代码块（替换为占位符），再逐行处理其余 Markdown
            List<String> codeBlocks = new ArrayList<>();
            String processed = markdown;
            Matcher codeMatcher = CODE_BLOCK_PATTERN.matcher(markdown);
            while (codeMatcher.find()) {
                codeBlocks.add(codeMatcher.group(2));
            }
            // 用占位符替换代码块，避免代码内容被逐行解析
            processed = CODE_BLOCK_PATTERN.matcher(processed).replaceAll(match -> {
                int idx = codeBlocks.indexOf(match.group(2));
                return "\n@@CODEBLOCK_" + idx + "@@\n";
            });

            // 按行分割处理
            String[] lines = processed.split("\n");
            int i = 0;
            while (i < lines.length) {
                String line = lines[i].trim();

                // 代码块占位符
                if (line.startsWith("@@CODEBLOCK_") && line.endsWith("@@")) {
                    int idx = Integer.parseInt(line.replaceAll("[^0-9]", ""));
                    if (idx < codeBlocks.size()) {
                        addCodeBlock(doc, codeBlocks.get(idx));
                    }
                    i++;
                    continue;
                }

                // 空行
                if (line.isEmpty()) {
                    i++;
                    continue;
                }

                // 标题
                if (line.startsWith("#")) {
                    addHeading(doc, line);
                    i++;
                    continue;
                }

                // 水平线
                if (line.equals("---") || line.equals("***") || line.equals("___")) {
                    addHorizontalRule(doc);
                    i++;
                    continue;
                }

                // 引用块
                if (line.startsWith(">")) {
                    addQuote(doc, line.substring(1).trim());
                    i++;
                    continue;
                }

                // 无序列表
                if (line.startsWith("- ") || line.startsWith("* ")) {
                    addListItem(doc, line.substring(2).trim(), false);
                    i++;
                    continue;
                }

                // 有序列表
                if (line.matches("^\\d+\\.\\s.+")) {
                    String content = line.replaceFirst("^\\d+\\.\\s", "");
                    addListItem(doc, content, true);
                    i++;
                    continue;
                }

                // 表格（连续的 | 行）
                if (line.startsWith("|") && line.endsWith("|")) {
                    List<String> tableLines = new ArrayList<>();
                    while (i < lines.length && lines[i].trim().startsWith("|") && lines[i].trim().endsWith("|")) {
                        tableLines.add(lines[i].trim());
                        i++;
                    }
                    addTable(doc, tableLines);
                    continue;
                }

                // 普通段落
                addParagraphWithBold(doc, line);
                i++;
            }

            doc.write(baos);
            log.info("WordDocumentService: Markdown -> Word 转换完成, 大小={}bytes", baos.size());
            return baos.toByteArray();

        } catch (Exception e) {
            log.error("WordDocumentService: Markdown -> Word 转换失败: {}", e.getMessage(), e);
            throw new RuntimeException("Word 文档生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 添加标题（根据 # 数量映射到 Word 标题级别）。
     */
    private void addHeading(XWPFDocument doc, String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        String text = line.substring(level).trim();
        int headingLevel = Math.min(level, 6);
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Heading" + headingLevel);
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(switch (headingLevel) {
            case 1 -> 18;
            case 2 -> 16;
            case 3 -> 14;
            case 4 -> 13;
            default -> 12;
        });
    }

    /**
     * 添加普通段落，支持 **粗体** 标记。
     */
    private void addParagraphWithBold(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        addFormattedRuns(p, text);
    }

    /**
     * 添加代码块（等宽字体 + 灰色底色）。
     */
    private void addCodeBlock(XWPFDocument doc, String code) {
        XWPFParagraph p = doc.createParagraph();
        p.setBorderTop(Borders.SINGLE);
        p.setBorderBottom(Borders.SINGLE);
        p.setBorderLeft(Borders.SINGLE);
        p.setBorderRight(Borders.SINGLE);
        // 代码可能有多行
        String[] codeLines = code.split("\n");
        for (int j = 0; j < codeLines.length; j++) {
            XWPFRun run = p.createRun();
            run.setText(codeLines[j]);
            run.setFontFamily("Courier New");
            run.setFontSize(10);
            if (j < codeLines.length - 1) {
                run.addBreak();
            }
        }
    }

    /**
     * 添加引用块（斜体 + 左缩进）。
     */
    private void addQuote(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setIndentationLeft(720); // 0.5 inch
        p.setBorderLeft(Borders.SINGLE);
        addFormattedRuns(p, text);
        // 设置斜体
        for (XWPFRun run : p.getRuns()) {
            run.setItalic(true);
        }
    }

    /**
     * 添加列表项。
     */
    private void addListItem(XWPFDocument doc, String text, boolean ordered) {
        XWPFParagraph p = doc.createParagraph();
        p.setIndentationLeft(360);
        XWPFRun bullet = p.createRun();
        bullet.setText(ordered ? "• " : "• ");
        addFormattedRuns(p, text);
    }

    /**
     * 添加表格。
     */
    private void addTable(XWPFDocument doc, List<String> tableLines) {
        if (tableLines.isEmpty()) return;

        // 跳过分隔行（|---|---|）
        List<String[]> rows = new ArrayList<>();
        for (String line : tableLines) {
            if (line.contains("---")) continue;
            String[] cells = line.substring(1, line.length() - 1).split("\\|");
            for (int j = 0; j < cells.length; j++) {
                cells[j] = cells[j].trim();
            }
            rows.add(cells);
        }
        if (rows.isEmpty()) return;

        int cols = rows.get(0).length;
        XWPFTable table = doc.createTable(rows.size(), cols);

        // 设置表格宽度为自动
        CTTbl ctTbl = table.getCTTbl();
        CTTblPr pr = ctTbl.getTblPr();
        CTTblWidth width = pr.addNewTblW();
        width.setType(STTblWidth.AUTO);
        width.setW(0);

        for (int r = 0; r < rows.size(); r++) {
            String[] rowData = rows.get(r);
            for (int c = 0; c < cols && c < rowData.length; c++) {
                XWPFTableCell cell = table.getRow(r).getCell(c);
                cell.setText(rowData[c]);
                // 表头加粗
                if (r == 0) {
                    XWPFParagraph p = cell.getParagraphs().get(0);
                    for (XWPFRun run : p.getRuns()) {
                        run.setBold(true);
                    }
                }
            }
        }
    }

    /**
     * 添加水平线。
     */
    private void addHorizontalRule(XWPFDocument doc) {
        XWPFParagraph p = doc.createParagraph();
        p.setBorderBottom(Borders.SINGLE);
    }

    /**
     * 解析 **粗体** 标记并添加格式化 runs。
     */
    private void addFormattedRuns(XWPFParagraph p, String text) {
        Matcher m = BOLD_PATTERN.matcher(text);
        int lastEnd = 0;
        while (m.find()) {
            // 粗体前的普通文本
            if (m.start() > lastEnd) {
                XWPFRun run = p.createRun();
                run.setText(text.substring(lastEnd, m.start()));
            }
            // 粗体文本
            XWPFRun boldRun = p.createRun();
            boldRun.setText(m.group(1));
            boldRun.setBold(true);
            lastEnd = m.end();
        }
        // 剩余普通文本
        if (lastEnd < text.length()) {
            XWPFRun run = p.createRun();
            run.setText(text.substring(lastEnd));
        }
        // 如果没有任何 runs（空文本），添加空 run
        if (p.getRuns().isEmpty()) {
            p.createRun().setText(text);
        }
    }
}