package com.cris.customerportal.controller;

import com.cris.customerportal.service.DocumentScanService;
import com.cris.customerportal.service.DbaEmailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * DocumentController -- Java port of Node:
 *   documentController.ts  -> scan-only endpoints
 *   panController.ts       -> PAN file upload + verify
 *   gstinController.ts     -> GSTIN CRUD + file management
 *   customerRoutes.ts      -> file upload routes for customer codes
 *
 * All Oracle queries target the tables defined in database/legacy-oracle/:
 *   MEMCUSTOMER       -- new customer registrations
 *   MEMCUSTOMERGSTIN  -- per-state GSTIN records
 *   MEMGLBLCUST       -- legacy global customer codes (customer_code equivalent)
 *   MEMGLBLHNDGAGNT   -- legacy handling agent codes
 */
@RestController
@CrossOrigin("*")
public class DocumentController {

    private final DocumentScanService scanner;
    private final DataSource dataSource;
    private final DbaEmailService emailService;
    private final Path gstnUploadPath;
    private final Path panUploadPath;

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/jpg", "image/png"
    );

    public DocumentController(
            DocumentScanService scanner,
            DataSource dataSource,
            DbaEmailService emailService,
            @Value("${app.upload-dir:uploads/gstin}") String gstnDir,
            @Value("${app.pan-upload-dir:uploads/pan}") String panDir
    ) {
        this.scanner       = scanner;
        this.dataSource    = dataSource;
        this.emailService  = emailService;
        this.gstnUploadPath = Paths.get(gstnDir).toAbsolutePath().normalize();
        this.panUploadPath  = Paths.get(panDir).toAbsolutePath().normalize();
    }

    // =========================================================================
    // Scan-only endpoints (no DB write)  --  POST /api/documents/pan/scan
    //                                    --  POST /api/documents/gstin/scan
    // These are standalone scan-and-return endpoints (no persistence).
    // =========================================================================

    @PostMapping(value = "/api/documents/pan/scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> scanPan(@RequestPart("file") MultipartFile file) {
        try {
            validateFile(file);
            DocumentScanService.ScanResult result = scanner.scan("pan", file.getBytes());
            if (result.pan == null) {
                return ResponseEntity.unprocessableEntity().body(Map.of(
                        "success", false,
                        "message", "PAN number could not be detected from the uploaded document. Please upload a clearer PAN Card PDF or enter the PAN manually."
                ));
            }
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", Map.of("pan", result.pan, "confidence", result.confidence)
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Document scan failed: " + e.getMessage()));
        }
    }

    @PostMapping(value = "/api/documents/gstin/scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> scanGstin(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String address
    ) {
        try {
            validateFile(file);
            DocumentScanService.ScanResult result = scanner.scan("gstin", file.getBytes());
            if (result.gstin == null || result.address == null) {
                return ResponseEntity.unprocessableEntity().body(Map.of(
                        "success", false,
                        "message", "GSTIN or registered address could not be detected. Please upload a clearer GST certificate."
                ));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("gstin",      result.gstin);
            data.put("address",    result.address);
            data.put("city",       result.city);
            data.put("pincode",    result.pincode);
            data.put("legalName",  result.legalName);
            data.put("stateCode",  result.stateCode);
            data.put("state",      result.state);
            data.put("confidence", result.confidence);

            if (address != null && !address.trim().isEmpty()) {
                boolean matches = DocumentScanService.addressesMatch(address, result.address);
                data.put("addressMatch", matches);
            }
            return ResponseEntity.ok(Map.of("success", true, "data", data));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Document scan failed: " + e.getMessage()));
        }
    }

    // =========================================================================
    // PAN file upload -- PUT /api/customers/{customerCode}/pan-file
    // Ported from Node panController.ts uploadPanFile()
    // =========================================================================

    @PutMapping(value = "/api/customers/{customerCode}/pan-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadPanFile(
            @PathVariable String customerCode,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String panNumber
    ) {
        String code = customerCode.trim().toUpperCase();
        if (code.isEmpty()) return bad("Customer Code is required");
        if (file == null || file.isEmpty()) return bad("PAN PDF file is required");

        try {
            validateFile(file);
            DocumentScanService.ScanResult scan = scanner.scan("pan", file.getBytes());

            if (scan.pan == null) {
                return ResponseEntity.unprocessableEntity().body(Map.of(
                        "success", false,
                        "message", "PAN number could not be detected from the uploaded document. Please upload a clearer PAN Card PDF or enter the PAN manually."
                ));
            }

            // If caller supplied a PAN, verify it matches the scanned PAN
            if (panNumber != null && !panNumber.trim().isEmpty()) {
                String supplied = panNumber.trim().toUpperCase();
                if (!DocumentScanService.isValidPAN(supplied) || !supplied.equals(scan.pan)) {
                    return ResponseEntity.unprocessableEntity().body(Map.of(
                            "success", false,
                            "message", "PAN number does not match the uploaded PAN Card."
                    ));
                }
            }

            // Determine which Oracle table holds this code (MEMGLBLCUST or MEMGLBLHNDGAGNT)
            String tableName = null;
            String codeCol   = null;
            try (Connection conn = dataSource.getConnection()) {
                if (rowExists(conn, "MEMGLBLCUST", "MAVGLBLCUSTCODE", code)) {
                    tableName = "MEMGLBLCUST";
                    codeCol   = "MAVGLBLCUSTCODE";
                } else if (rowExists(conn, "MEMGLBLHNDGAGNT", "MAVHNDGAGNTCODE", code)) {
                    tableName = "MEMGLBLHNDGAGNT";
                    codeCol   = "MAVHNDGAGNTCODE";
                }
            }

            if (tableName == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "message", "Code not found."));
            }

            // Save file to disk
            Files.createDirectories(panUploadPath);
            String safeName = sanitize(code) + "_PAN_" + System.currentTimeMillis() + ".pdf";
            Path dest = panUploadPath.resolve(safeName).normalize();
            if (!dest.startsWith(panUploadPath)) return bad("Invalid file path");
            file.transferTo(dest);
            String relativePath = "pan/" + safeName;

            // Update Oracle record
            String sql = "UPDATE " + tableName + " SET MAVCUSTPANNUMB = ? WHERE " + codeCol + " = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, scan.pan);
                ps.setString(2, code);
                ps.executeUpdate();
            }

            // Audit email (non-fatal)
            try {
                emailService.sendGenericAudit("PAN Upload", tableName, code,
                        "MAVCUSTPANNUMB set to " + scan.pan + "; file saved to " + relativePath);
            } catch (Exception ignored) {}

            return ResponseEntity.ok(Map.of(
                    "success",  true,
                    "message",  "PAN file uploaded successfully",
                    "data",     Map.of("fileName", file.getOriginalFilename(), "filePath", relativePath, "pan", scan.pan)
            ));

        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (IOException | SQLException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Upload failed: " + e.getMessage()));
        }
    }

    // =========================================================================
    // GSTIN CRUD -- ported from gstinController.ts
    //
    // GET    /api/customers/{code}/gstins
    // POST   /api/customers/{code}/gstins
    // PUT    /api/customers/{code}/gstins/{id}
    // DELETE /api/customers/{code}/gstins/{id}
    // GET    /api/customers/{code}/gstins/{id}/file
    //
    // NOTE: These operate on MEMCUSTOMERGSTIN (new registrations), not the
    // legacy MEMGLBLCUST table.  The Node version used MySQL column names;
    // the Oracle column names come from database/legacy-oracle/01_create_tables.sql.
    // =========================================================================

    @GetMapping("/api/customers/{code}/gstins")
    public ResponseEntity<?> getGstins(@PathVariable("code") String customerCode) {
        String code = customerCode.trim().toUpperCase();
        String sql  = "SELECT MAVGSTNID, MAVCUSTCODE, MAVGSTSTATE, MAVGSTSTATECD, MAVGSTNUMB, " +
                      "MAVGSTNFILNM, MAVGSTNFILTYP, MAVGSTNFILPATH, MAVGSTNREGDADDR, " +
                      "MACGSTVRFNSTAT, MACADDRVRFNSTAT, MACACTIVEFLAG, MADCREATEDDATE, MADUPDATEDDATE " +
                      "FROM MEMCUSTOMERGSTIN WHERE MAVCUSTCODE = ? ORDER BY MAVGSTSTATECD, MAVGSTSTATE";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> list = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("gstinId",         rs.getLong("MAVGSTNID"));
                    row.put("customerCode",     rs.getString("MAVCUSTCODE"));
                    row.put("state",            rs.getString("MAVGSTSTATE"));
                    row.put("stateCode",        rs.getString("MAVGSTSTATECD"));
                    row.put("gstinNumber",      rs.getString("MAVGSTNUMB"));
                    row.put("fileName",         rs.getString("MAVGSTNFILNM"));
                    row.put("fileType",         rs.getString("MAVGSTNFILTYP"));
                    row.put("filePath",         rs.getString("MAVGSTNFILPATH"));
                    row.put("hasFile",          rs.getString("MAVGSTNFILPATH") != null);
                    row.put("registeredAddress",rs.getString("MAVGSTNREGDADDR"));
                    row.put("gstinStatus",      rs.getString("MACGSTVRFNSTAT"));
                    row.put("addressStatus",    rs.getString("MACADDRVRFNSTAT"));
                    row.put("activeFlag",       rs.getString("MACACTIVEFLAG"));
                    row.put("createdDate",      rs.getString("MADCREATEDDATE"));
                    row.put("updatedDate",      rs.getString("MADUPDATEDDATE"));
                    list.add(row);
                }
                return ResponseEntity.ok(Map.of("success", true, "data", list));
            }
        } catch (SQLException e) {
            return err("Database error: " + e.getMessage());
        }
    }

    @PostMapping(value = "/api/customers/{code}/gstins", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> createGstin(
            @PathVariable("code") String customerCode,
            @RequestParam Map<String, String> params,
            @RequestPart(value = "file", required = false) MultipartFile file
    ) {
        String code        = customerCode.trim().toUpperCase();
        String state       = params.getOrDefault("state", "").trim();
        String stateCode   = params.getOrDefault("stateCode", "").trim();
        String gstinNumber = params.getOrDefault("gstinNumber", "").trim().toUpperCase();
        String activeFlag  = params.getOrDefault("activeFlag", "Y").toUpperCase();

        if (state.isEmpty())       return bad("State is required");
        if (gstinNumber.isEmpty()) return bad("GSTIN number is required");
        if (!DocumentScanService.isValidGSTIN(gstinNumber)) return bad("Invalid GSTIN format");

        try (Connection conn = dataSource.getConnection()) {
            // Customer must exist
            if (!rowExists(conn, "MEMCUSTOMER", "MAVCUSTCODE", code)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "message", "Customer Code not found."));
            }

            // PAN<->GSTIN consistency check
            String pan = getCustomerPan(conn, code);
            if (!DocumentScanService.isGstinMatchingPan(gstinNumber, pan)) {
                return bad("GSTIN does not match the PAN number");
            }

            // Duplicate GSTIN check
            if (gstinExistsForCustomer(conn, code, gstinNumber, null)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("success", false, "message", "GSTIN already exists for this customer"));
            }

            // OCR-verify file if provided
            String verifiedAddress = null;
            String gstinStatus = "PENDING";
            String addrStatus  = "PENDING";
            String filePath    = null;
            String fileName    = null;
            String fileType    = null;

            if (file != null && !file.isEmpty()) {
                validateFile(file);
                DocumentScanService.ScanResult scan = scanner.scan("gstin", file.getBytes());
                if (scan.gstin == null || scan.address == null) {
                    return ResponseEntity.unprocessableEntity().body(Map.of("success", false,
                            "message", "GSTIN or registered address could not be detected. Please upload a clearer GST certificate."));
                }
                if (!DocumentScanService.isValidGSTIN(scan.gstin) || !scan.gstin.equals(gstinNumber)) {
                    return ResponseEntity.unprocessableEntity().body(Map.of("success", false,
                            "message", "GSTIN number does not match the uploaded GST certificate."));
                }
                String userAddress = params.getOrDefault("address", "").trim();
                if (!userAddress.isEmpty() && !DocumentScanService.addressesMatch(userAddress, scan.address)) {
                    return ResponseEntity.unprocessableEntity().body(Map.of("success", false,
                            "message", "Business address does not match the GST certificate."));
                }
                verifiedAddress = scan.address;
                gstinStatus = "VERIFIED";
                addrStatus  = "VERIFIED";

                Files.createDirectories(gstnUploadPath);
                String safeName = sanitize(code) + "_" + sanitize(gstinNumber) + "_" + System.currentTimeMillis() + ".pdf";
                Path dest = gstnUploadPath.resolve(safeName).normalize();
                file.transferTo(dest);
                filePath = "gstin/" + safeName;
                fileName = file.getOriginalFilename();
                fileType = file.getContentType();
            }

            String insertSql = "INSERT INTO MEMCUSTOMERGSTIN " +
                    "(MAVCUSTCODE, MAVGSTSTATE, MAVGSTSTATECD, MAVGSTNUMB, " +
                    "MAVGSTNFILNM, MAVGSTNFILTYP, MAVGSTNFILPATH, MAVGSTNREGDADDR, " +
                    "MACGSTVRFNSTAT, MACADDRVRFNSTAT, MACACTIVEFLAG, MADCREATEDDATE) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, SYSDATE)";
            conn.setAutoCommit(false);
            long gstinId;
            try (PreparedStatement ps = conn.prepareStatement(insertSql, new String[]{"MAVGSTNID"})) {
                ps.setString(1, code);
                ps.setString(2, state.length() > 50 ? state.substring(0, 50) : state);
                ps.setString(3, stateCode.length() > 2 ? stateCode.substring(0, 2) : stateCode);
                ps.setString(4, gstinNumber.length() > 15 ? gstinNumber.substring(0, 15) : gstinNumber);
                ps.setString(5, fileName);
                ps.setString(6, fileType);
                ps.setString(7, filePath);
                ps.setString(8, verifiedAddress);
                ps.setString(9, gstinStatus);
                ps.setString(10, addrStatus);
                ps.setString(11, activeFlag);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    gstinId = keys.next() ? keys.getLong(1) : -1;
                }
            }
            conn.commit();
            conn.setAutoCommit(true);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("success", true, "message", "GSTIN added successfully",
                            "data", Map.of("gstinId", gstinId)));

        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (IOException | SQLException e) {
            return err("Operation failed: " + e.getMessage());
        }
    }

    @PutMapping(value = "/api/customers/{code}/gstins/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> updateGstin(
            @PathVariable("code") String customerCode,
            @PathVariable("id") Long gstinId,
            @RequestParam Map<String, String> params,
            @RequestPart(value = "file", required = false) MultipartFile file
    ) {
        String code = customerCode.trim().toUpperCase();
        try (Connection conn = dataSource.getConnection()) {
            Map<String, Object> existing = getGstinRow(conn, gstinId, code);
            if (existing == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "message", "GSTIN not found for this customer"));
            }

            String gstinNumber = params.getOrDefault("gstinNumber", String.valueOf(existing.get("MAVGSTNUMB")))
                    .trim().toUpperCase();
            String state     = params.getOrDefault("state", String.valueOf(existing.get("MAVGSTSTATE"))).trim();
            String stateCode = params.getOrDefault("stateCode", safeStr(existing.get("MAVGSTSTATECD"))).trim();
            String activeFlag = params.getOrDefault("activeFlag", safeStr(existing.get("MACACTIVEFLAG"))).toUpperCase();

            if (!DocumentScanService.isValidGSTIN(gstinNumber)) return bad("Invalid GSTIN format");

            String pan = getCustomerPan(conn, code);
            if (!DocumentScanService.isGstinMatchingPan(gstinNumber, pan)) return bad("GSTIN does not match the PAN number");

            if (gstinExistsForCustomer(conn, code, gstinNumber, gstinId)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("success", false, "message", "GSTIN already exists for this customer"));
            }

            String filePath        = safeStr(existing.get("MAVGSTNFILPATH"));
            String fileName        = safeStr(existing.get("MAVGSTNFILNM"));
            String fileType        = safeStr(existing.get("MAVGSTNFILTYP"));
            String verifiedAddress = safeStr(existing.get("MAVGSTNREGDADDR"));
            String gstinStatus     = safeStr(existing.get("MACGSTVRFNSTAT"));
            String addrStatus      = safeStr(existing.get("MACADDRVRFNSTAT"));

            if (file != null && !file.isEmpty()) {
                validateFile(file);
                DocumentScanService.ScanResult scan = scanner.scan("gstin", file.getBytes());
                if (scan.gstin == null || scan.address == null) {
                    return ResponseEntity.unprocessableEntity().body(Map.of("success", false,
                            "message", "GSTIN or registered address could not be detected."));
                }
                if (!scan.gstin.equals(gstinNumber)) {
                    return ResponseEntity.unprocessableEntity().body(Map.of("success", false,
                            "message", "GSTIN number does not match the uploaded GST certificate."));
                }
                String userAddress = params.getOrDefault("address", "").trim();
                if (!userAddress.isEmpty() && !DocumentScanService.addressesMatch(userAddress, scan.address)) {
                    return ResponseEntity.unprocessableEntity().body(Map.of("success", false,
                            "message", "Business address does not match the GST certificate."));
                }
                verifiedAddress = scan.address;
                gstinStatus = "VERIFIED";
                addrStatus  = "VERIFIED";

                Files.createDirectories(gstnUploadPath);
                String safeName = sanitize(code) + "_" + sanitize(gstinNumber) + "_" + System.currentTimeMillis() + ".pdf";
                Path dest = gstnUploadPath.resolve(safeName).normalize();
                file.transferTo(dest);
                filePath = "gstin/" + safeName;
                fileName = file.getOriginalFilename();
                fileType = file.getContentType();
            }

            String updateSql = "UPDATE MEMCUSTOMERGSTIN SET " +
                    "MAVGSTSTATE = ?, MAVGSTSTATECD = ?, MAVGSTNUMB = ?, " +
                    "MAVGSTNFILNM = ?, MAVGSTNFILTYP = ?, MAVGSTNFILPATH = ?, MAVGSTNREGDADDR = ?, " +
                    "MACGSTVRFNSTAT = ?, MACADDRVRFNSTAT = ?, MACACTIVEFLAG = ?, MADUPDATEDDATE = SYSDATE " +
                    "WHERE MAVGSTNID = ? AND MAVCUSTCODE = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, state);
                ps.setString(2, stateCode);
                ps.setString(3, gstinNumber);
                ps.setString(4, fileName);
                ps.setString(5, fileType);
                ps.setString(6, filePath);
                ps.setString(7, verifiedAddress);
                ps.setString(8, gstinStatus);
                ps.setString(9, addrStatus);
                ps.setString(10, activeFlag);
                ps.setLong(11, gstinId);
                ps.setString(12, code);
                ps.executeUpdate();
            }
            return ResponseEntity.ok(Map.of("success", true, "message", "GSTIN updated successfully",
                    "data", Map.of("gstinId", gstinId)));

        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (IOException | SQLException e) {
            return err("Operation failed: " + e.getMessage());
        }
    }

    @DeleteMapping("/api/customers/{code}/gstins/{id}")
    public ResponseEntity<?> deleteGstin(
            @PathVariable("code") String customerCode,
            @PathVariable("id") Long gstinId
    ) {
        String code = customerCode.trim().toUpperCase();
        try (Connection conn = dataSource.getConnection()) {
            Map<String, Object> existing = getGstinRow(conn, gstinId, code);
            if (existing == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "message", "GSTIN not found for this customer"));
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM MEMCUSTOMERGSTIN WHERE MAVGSTNID = ? AND MAVCUSTCODE = ?")) {
                ps.setLong(1, gstinId);
                ps.setString(2, code);
                ps.executeUpdate();
            }
            return ResponseEntity.ok(Map.of("success", true, "message", "GSTIN deleted successfully"));
        } catch (SQLException e) {
            return err("Database error: " + e.getMessage());
        }
    }

    @GetMapping("/api/customers/{code}/gstins/{id}/file")
    public ResponseEntity<?> getGstinFile(
            @PathVariable("code") String customerCode,
            @PathVariable("id") Long gstinId
    ) {
        String code = customerCode.trim().toUpperCase();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT MAVGSTNFILNM, MAVGSTNFILTYP, MAVGSTNFILPATH FROM MEMCUSTOMERGSTIN " +
                     "WHERE MAVGSTNID = ? AND MAVCUSTCODE = ?")) {
            ps.setLong(1, gstinId);
            ps.setString(2, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getString("MAVGSTNFILPATH") == null) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(Map.of("success", false, "message", "GSTIN file not found"));
                }
                String relPath  = rs.getString("MAVGSTNFILPATH");
                String fileName = rs.getString("MAVGSTNFILNM");
                String mimeType = rs.getString("MAVGSTNFILTYP");

                // File path is relative to uploads root; gstnUploadPath parent is uploads/
                Path fileFull = gstnUploadPath.getParent().resolve(relPath).normalize();
                byte[] bytes = Files.readAllBytes(fileFull);

                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + encode(fileName) + "\"")
                        .contentType(mimeType != null ? MediaType.parseMediaType(mimeType) : MediaType.APPLICATION_PDF)
                        .body(bytes);
            }
        } catch (IOException | SQLException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false, "message", "GSTIN file not found on disk"));
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("File is required");
        if (file.getSize() > MAX_FILE_SIZE)  throw new IllegalArgumentException("File must be <= 5 MB");
        String ct = file.getContentType();
        if (ct == null || !ALLOWED_TYPES.contains(ct))
            throw new IllegalArgumentException("File must be PDF, JPEG, or PNG");
    }

    private boolean rowExists(Connection conn, String table, String col, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM " + table + " WHERE " + col + " = ?")) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private String getCustomerPan(Connection conn, String code) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT MAVCUSTPANNUMB FROM MEMCUSTOMER WHERE MAVCUSTCODE = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("MAVCUSTPANNUMB") : "";
            }
        }
    }

    private boolean gstinExistsForCustomer(Connection conn, String code, String gstin, Long excludeId) throws SQLException {
        String sql = excludeId != null
                ? "SELECT 1 FROM MEMCUSTOMERGSTIN WHERE MAVCUSTCODE = ? AND MAVGSTNUMB = ? AND MAVGSTNID <> ?"
                : "SELECT 1 FROM MEMCUSTOMERGSTIN WHERE MAVCUSTCODE = ? AND MAVGSTNUMB = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            ps.setString(2, gstin);
            if (excludeId != null) ps.setLong(3, excludeId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private Map<String, Object> getGstinRow(Connection conn, long id, String code) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM MEMCUSTOMERGSTIN WHERE MAVGSTNID = ? AND MAVCUSTCODE = ?")) {
            ps.setLong(1, id);
            ps.setString(2, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, Object> row = new HashMap<>();
                ResultSetMetaData meta = rs.getMetaData();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnName(i), rs.getObject(i));
                }
                return row;
            }
        }
    }

    private String safeStr(Object o) { return o != null ? o.toString() : ""; }

    private String sanitize(String value) {
        return value.trim().replaceAll("[^a-zA-Z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "")
                .substring(0, Math.min(value.length(), 80));
    }

    private String encode(String name) {
        if (name == null) return "document";
        try { return java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return name; }
    }

    private ResponseEntity<?> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", msg));
    }

    private ResponseEntity<?> err(String msg) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", msg));
    }
}