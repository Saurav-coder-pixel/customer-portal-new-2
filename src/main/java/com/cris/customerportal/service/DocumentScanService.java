package com.cris.customerportal.service;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/**
 * DocumentScanService -- Java port of Node backend/src/utils/documentScanner.ts
 *
 * Flow:
 *   PDF  -> PDFBox text extraction -> if empty -> PDFBox render pages -> Tess4J OCR
 *   Image -> Tess4J OCR
 *
 * Supports kind = "pan" or "gstin".
 * All OCR-correction and address-matching logic is ported from validators.ts / documentScanner.ts.
 */
@Service
public class DocumentScanService {

    private final String tessdataDir;

    public DocumentScanService(@Value("${app.tessdata-dir:backend}") String tessdataDir) {
        this.tessdataDir = tessdataDir;
    }

    // -------------------------------------------------------------------------
    // Public result types
    // -------------------------------------------------------------------------

    public static class ScanResult {
        public String pan;
        public String gstin;
        public String address;
        public String city;
        public String pincode;
        public String legalName;
        public String stateCode;
        public String state;
        public double confidence;
        public String rawText;
    }

    // -------------------------------------------------------------------------
    // Patterns (ported from documentScanner.ts)
    // -------------------------------------------------------------------------

    private static final Pattern PAN_EXACT    = Pattern.compile("[A-Z]{5}[0-9]{4}[A-Z]");
    private static final Pattern PAN_CAND     = Pattern.compile("[A-Z0-9]{10}");
    private static final Pattern GSTIN_EXACT  = Pattern.compile("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]");
    private static final Pattern GSTIN_CAND   = Pattern.compile("[A-Z0-9]{15}");
    private static final Pattern PIN_PATTERN  = Pattern.compile("\\b[1-9][0-9]{5}\\b");

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public ScanResult scan(String kind, byte[] data) throws IOException {
        boolean isPdf = data.length > 4
                && (data[0] & 0xFF) == 0x25
                && (data[1] & 0xFF) == 0x50
                && (data[2] & 0xFF) == 0x44
                && (data[3] & 0xFF) == 0x46;  // %PDF

        String text;
        double confidence;

        if (isPdf) {
            String extracted = extractPdfText(data);
            if (extracted == null || extracted.trim().isEmpty()) {
                // OCR fallback: render PDF pages to images and OCR them
                text = ocrPdfPages(data);
                confidence = 0.90;
            } else {
                text = extracted;
                confidence = 0.98;
            }
        } else {
            // Image (JPEG / PNG)
            text = ocrImage(data);
            confidence = 0.90;
        }

        return buildResult(kind, text, confidence);
    }

    // -------------------------------------------------------------------------
    // PDF text extraction via PDFBox
    // -------------------------------------------------------------------------

    private String extractPdfText(byte[] data) {
        try (PDDocument doc = Loader.loadPDF(data)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // OCR via Tess4J
    // -------------------------------------------------------------------------

    private Tesseract makeTesseract() {
        Tesseract t = new Tesseract();
        t.setDatapath(tessdataDir);
        t.setLanguage("eng");
        return t;
    }

    private String ocrImage(byte[] data) throws IOException {
        Tesseract t = makeTesseract();
        try {
            java.awt.image.BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
            if (img == null) throw new IOException("Unsupported image format");
            return t.doOCR(img);
        } catch (TesseractException e) {
            throw new IOException("OCR failed: " + e.getMessage(), e);
        }
    }

    private String ocrPdfPages(byte[] data) throws IOException {
        StringBuilder sb = new StringBuilder();
        Tesseract t = makeTesseract();
        try (PDDocument doc = Loader.loadPDF(data)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pages = Math.min(doc.getNumberOfPages(), 3);
            for (int p = 0; p < pages; p++) {
                BufferedImage img = renderer.renderImageWithDPI(p, 200);
                try {
                    sb.append(t.doOCR(img)).append("\n");
                } catch (TesseractException e) {
                    // skip page on OCR failure
                }
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Result builder -- ported from documentScanner.ts scanDocument()
    // -------------------------------------------------------------------------

    private ScanResult buildResult(String kind, String text, double confidence) {
        ScanResult r = new ScanResult();
        r.rawText   = normalizeText(text);
        r.confidence = confidence;

        if ("pan".equals(kind)) {
            r.pan = findPan(r.rawText);
        } else if ("gstin".equals(kind)) {
            r.gstin = findGstin(r.rawText);
            StructuredAddress sa = extractStructuredAddress(text);
            r.address   = sa.address != null ? sa.address : extractAddress(text);
            r.pincode   = sa.pincode != null ? sa.pincode : extractPin(r.address);
            r.city      = sa.city != null ? sa.city : extractCity(r.address);
            r.legalName = labelledValue(text, new String[]{"legal name", "trade name"});
            r.stateCode = r.gstin != null && r.gstin.length() >= 2 ? r.gstin.substring(0, 2) : null;
            r.state     = sa.state != null ? sa.state : getStateNameFromCode(r.stateCode);
        }

        return r;
    }

    // -------------------------------------------------------------------------
    // PAN detection -- ported from findPan()
    // -------------------------------------------------------------------------

    private String findPan(String text) {
        String clean = text.toUpperCase().replaceAll("[\\s:-]+", "");
        // 1. Direct exact match
        Matcher m = PAN_EXACT.matcher(clean);
        while (m.find()) {
            String cand = m.group();
            if (isValidPAN(cand)) return cand;
        }
        // 2. Candidate + OCR correction
        Matcher mc = PAN_CAND.matcher(clean);
        while (mc.find()) {
            String corrected = correctPanOcr(mc.group());
            if (corrected != null) return corrected;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // GSTIN detection -- ported from findGstin()
    // -------------------------------------------------------------------------

    private String findGstin(String text) {
        String clean = text.toUpperCase().replaceAll("[\\s:-]+", "");
        Matcher m = GSTIN_EXACT.matcher(clean);
        while (m.find()) {
            String cand = m.group();
            if (isValidGSTIN(cand)) return cand;
        }
        Matcher mc = GSTIN_CAND.matcher(clean);
        while (mc.find()) {
            String corrected = correctGstinOcr(mc.group());
            if (corrected != null) return corrected;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Validators -- ported from validators.ts
    // -------------------------------------------------------------------------

    public static boolean isValidPAN(String pan) {
        return pan != null && pan.matches("[A-Z]{5}[0-9]{4}[A-Z]{1}");
    }

    public static boolean isValidGSTIN(String gstin) {
        return gstin != null && gstin.matches("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[A-Z0-9]{1}Z[A-Z0-9]{1}");
    }

    public static boolean isGstinMatchingPan(String gstin, String pan) {
        if (gstin == null || pan == null) return false;
        String g = gstin.replaceAll("\\s+", "").toUpperCase();
        String p = pan.replaceAll("\\s+", "").toUpperCase();
        return g.length() == 15 && p.length() == 10 && g.substring(2, 12).equals(p);
    }

    // -------------------------------------------------------------------------
    // OCR correction -- ported from correctPanOcr() / correctGstinOcr()
    // -------------------------------------------------------------------------

    private static final Map<Character, Character> DIGIT_TO_LETTER = Map.of(
        '0','O', '1','I', '2','Z', '5','S', '6','G', '8','B'
    );
    private static final Map<Character, Character> LETTER_TO_DIGIT = Map.of(
        'O','0', 'Q','0', 'D','0',
        'I','1', 'L','1',
        'Z','2', 'S','5', 'G','6', 'B','8'
    );

    private String correctPanOcr(String candidate) {
        if (candidate == null || candidate.length() != 10) return null;
        char[] c = candidate.toUpperCase().toCharArray();
        // Pos 0-4: must be letters
        for (int i = 0; i < 5; i++) {
            if (Character.isDigit(c[i])) c[i] = DIGIT_TO_LETTER.getOrDefault(c[i], c[i]);
        }
        // Pos 5-8: must be digits
        for (int i = 5; i < 9; i++) {
            if (Character.isLetter(c[i])) c[i] = LETTER_TO_DIGIT.getOrDefault(c[i], c[i]);
        }
        // Pos 9: must be letter
        if (Character.isDigit(c[9])) c[9] = DIGIT_TO_LETTER.getOrDefault(c[9], c[9]);
        String result = new String(c);
        return isValidPAN(result) ? result : null;
    }

    private String correctGstinOcr(String candidate) {
        if (candidate == null || candidate.length() != 15) return null;
        char[] c = candidate.toUpperCase().toCharArray();
        // Pos 0-1: state code (digits)
        for (int i = 0; i < 2; i++) {
            if (Character.isLetter(c[i])) c[i] = LETTER_TO_DIGIT.getOrDefault(c[i], c[i]);
        }
        // Pos 2-6: 5 letters
        for (int i = 2; i < 7; i++) {
            if (Character.isDigit(c[i])) c[i] = DIGIT_TO_LETTER.getOrDefault(c[i], c[i]);
        }
        // Pos 7-10: 4 digits
        for (int i = 7; i < 11; i++) {
            if (Character.isLetter(c[i])) c[i] = LETTER_TO_DIGIT.getOrDefault(c[i], c[i]);
        }
        // Pos 11: letter
        if (Character.isDigit(c[11])) c[11] = DIGIT_TO_LETTER.getOrDefault(c[11], c[11]);
        // Pos 13: must be 'Z'
        if (c[13] != 'Z' && (c[13] == '2' || c[13] == '7' || c[13] == 'S')) c[13] = 'Z';
        String result = new String(c);
        return isValidGSTIN(result) ? result : null;
    }

    // -------------------------------------------------------------------------
    // Address helpers -- ported from documentScanner.ts
    // -------------------------------------------------------------------------

    /**
     * Normalises two address strings and returns true if they match.
     * Matches Node's addressesMatch() exactly:
     *   - case insensitive
     *   - PIN code priority (if both have a PIN and they differ -> no match)
     *   - full-contains OR 60% token overlap
     */
    public static boolean addressesMatch(String left, String right) {
        if (left == null || right == null) return false;
        String a = normalizeAddress(left);
        String b = normalizeAddress(right);
        if (a.isEmpty() || b.isEmpty()) return false;

        // PIN code priority
        Matcher aPin = PIN_PATTERN.matcher(a);
        Matcher bPin = PIN_PATTERN.matcher(b);
        String aPinStr = aPin.find() ? aPin.group() : null;
        String bPinStr = bPin.find() ? bPin.group() : null;
        if (aPinStr != null && bPinStr != null && !aPinStr.equals(bPinStr)) return false;

        if (a.equals(b) || a.contains(b) || b.contains(a)) return true;

        // Token overlap >= 60%
        Set<String> aTokens = tokenize(a);
        Set<String> bTokens = tokenize(b);
        long overlap = aTokens.stream().filter(bTokens::contains).count();
        int maxSize  = Math.max(aTokens.size(), bTokens.size());
        return maxSize > 0 && (double) overlap / maxSize >= 0.6;
    }

    public static String normalizeAddress(String value) {
        return value.toUpperCase()
                .replaceAll("[^A-Z0-9 ]", " ")
                .replaceAll("\\b(RD|STREET|ST)\\b", " ROAD ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static Set<String> tokenize(String s) {
        Set<String> set = new LinkedHashSet<>();
        for (String t : s.split("\\s+")) {
            if (t.length() > 1) set.add(t);
        }
        return set;
    }

    // -------------------------------------------------------------------------
    // Text / address extraction helpers
    // -------------------------------------------------------------------------

    private static String normalizeText(String value) {
        return value.replace("|", "I").replaceAll("\\s+", " ").trim();
    }

    private String labelledValue(String text, String[] labels) {
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            for (String label : labels) {
                if (line.toLowerCase().contains(label.toLowerCase())) {
                    String[] parts = line.split(":", 2);
                    if (parts.length > 1 && !parts[1].trim().isEmpty()) return parts[1].trim();
                    if (i + 1 < lines.length) return lines[i + 1].trim();
                }
            }
        }
        return null;
    }

    private String extractAddress(String text) {
        String value = labelledValue(text, new String[]{"registered address", "principal place of business", "business address", "address"});
        if (value == null) return null;
        String[] lines = text.split("\\r?\\n");
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().toLowerCase().contains(value.toLowerCase())) { start = i; break; }
        }
        String[] selected = start >= 0 ? Arrays.copyOfRange(lines, start, Math.min(start + 5, lines.length))
                                       : new String[]{value};
        String address = String.join(", ", selected).replaceAll("\\s+", " ").replaceAll(",\\s*,", ",").trim();
        return PIN_PATTERN.matcher(address).find() ? address : null;
    }

    private String extractPin(String address) {
        if (address == null) return null;
        Matcher m = PIN_PATTERN.matcher(address);
        return m.find() ? m.group() : null;
    }

    private String extractCity(String address) {
        if (address == null) return null;
        String[] parts = address.split(",");
        for (int i = 0; i < parts.length; i++) {
            if (PIN_PATTERN.matcher(parts[i]).find()) {
                String words = parts[i].replaceAll(PIN_PATTERN.pattern(), "").replaceAll("[-_]", "").trim();
                if (words.length() > 2) return words;
                if (i > 0 && parts[i - 1].trim().length() > 2) return parts[i - 1].trim();
            }
        }
        return null;
    }

    private static class StructuredAddress {
        String address, city, pincode, state;
    }

    private StructuredAddress extractStructuredAddress(String text) {
        StructuredAddress s = new StructuredAddress();
        String[] lines = text.split("\\r?\\n");
        String building = findLabelValue(lines, "Building No./Flat No");
        String premises = findLabelValue(lines, "Name Of Premises/Building");
        String road     = findLabelValue(lines, "Road/Street");
        String landmark = findLabelValue(lines, "Nearby Landmark");
        String locality = findLabelValue(lines, "Locality/Sub Locality");
        s.city          = findLabelValue(lines, "City/Town/Village");
        s.pincode       = findLabelValue(lines, "PIN Code");
        if (s.pincode == null) s.pincode = findLabelValue(lines, "Pincode");
        s.state         = findLabelValue(lines, "State");

        List<String> parts = new ArrayList<>();
        for (String p : new String[]{building, premises, road, landmark, locality}) {
            if (p != null) parts.add(p);
        }
        s.address = parts.isEmpty() ? null : String.join(", ", parts);
        return s;
    }

    private String findLabelValue(String[] lines, String label) {
        String lc = label.toLowerCase();
        for (String line : lines) {
            String ll = line.toLowerCase();
            if (ll.contains(lc)) {
                int idx = ll.indexOf(lc);
                String remaining = line.substring(idx + label.length()).trim();
                if (remaining.startsWith(":")) remaining = remaining.substring(1).trim();
                if (!remaining.isEmpty()) return remaining;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // State code map -- ported from validators.ts STATE_CODE_MAP
    // -------------------------------------------------------------------------

    private static final Map<String, String> STATE_CODE_MAP = new LinkedHashMap<>();
    static {
        STATE_CODE_MAP.put("01", "Jammu and Kashmir");
        STATE_CODE_MAP.put("02", "Himachal Pradesh");
        STATE_CODE_MAP.put("03", "Punjab");
        STATE_CODE_MAP.put("04", "Chandigarh");
        STATE_CODE_MAP.put("05", "Uttarakhand");
        STATE_CODE_MAP.put("06", "Haryana");
        STATE_CODE_MAP.put("07", "Delhi");
        STATE_CODE_MAP.put("08", "Rajasthan");
        STATE_CODE_MAP.put("09", "Uttar Pradesh");
        STATE_CODE_MAP.put("10", "Bihar");
        STATE_CODE_MAP.put("11", "Sikkim");
        STATE_CODE_MAP.put("12", "Arunachal Pradesh");
        STATE_CODE_MAP.put("13", "Nagaland");
        STATE_CODE_MAP.put("14", "Manipur");
        STATE_CODE_MAP.put("15", "Mizoram");
        STATE_CODE_MAP.put("16", "Tripura");
        STATE_CODE_MAP.put("17", "Meghalaya");
        STATE_CODE_MAP.put("18", "Assam");
        STATE_CODE_MAP.put("19", "West Bengal");
        STATE_CODE_MAP.put("20", "Jharkhand");
        STATE_CODE_MAP.put("21", "Odisha");
        STATE_CODE_MAP.put("22", "Chhattisgarh");
        STATE_CODE_MAP.put("23", "Madhya Pradesh");
        STATE_CODE_MAP.put("24", "Gujarat");
        STATE_CODE_MAP.put("25", "Daman and Diu");
        STATE_CODE_MAP.put("26", "Dadra and Nagar Haveli");
        STATE_CODE_MAP.put("27", "Maharashtra");
        STATE_CODE_MAP.put("28", "Andhra Pradesh");
        STATE_CODE_MAP.put("29", "Karnataka");
        STATE_CODE_MAP.put("30", "Goa");
        STATE_CODE_MAP.put("31", "Lakshadweep");
        STATE_CODE_MAP.put("32", "Kerala");
        STATE_CODE_MAP.put("33", "Tamil Nadu");
        STATE_CODE_MAP.put("34", "Puducherry");
        STATE_CODE_MAP.put("35", "Andaman and Nicobar Islands");
        STATE_CODE_MAP.put("36", "Telangana");
        STATE_CODE_MAP.put("37", "Andhra Pradesh");
        STATE_CODE_MAP.put("38", "Ladakh");
    }

    public static String getStateNameFromCode(String code) {
        if (code == null) return null;
        String padded = String.format("%02d", Integer.parseInt(code.replaceAll("[^0-9]", "0")));
        return STATE_CODE_MAP.get(padded);
    }
}