package com.chengming.kb;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class DocumentTextParser {
    public String parse(MultipartFile file, String extension) throws Exception {
        try (InputStream input = file.getInputStream()) {
            return parse(input, extension);
        }
    }

    public String parse(InputStream input, String extension) throws Exception {
        return switch (extension.toLowerCase()) {
            case "txt", "md" -> new String(input.readAllBytes(), StandardCharsets.UTF_8);
            case "docx" -> {
                try (XWPFDocument document = new XWPFDocument(input);
                     XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                    yield extractor.getText();
                }
            }
            case "pdf" -> {
                try (PDDocument document = Loader.loadPDF(input.readAllBytes())) {
                    yield new PDFTextStripper().getText(document);
                }
            }
            default -> throw new IllegalArgumentException("仅支持 PDF、DOCX、TXT、MD 格式");
        };
    }
}
