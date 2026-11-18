import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.SAXException;

public class VisitorSystem {
    private static final List<String> CSV_HEADER = List.of(
            "Visitor ID", "Name", "Contact", "Purpose", "Email", "Residence",
            "Check-in time", "Check-out time", "ID verified by", "ID verified at");
    private static final List<String> VISIT_PURPOSES = List.of(
            "Prospective Student – Campus Tour",
            "Admission Enquiry",
            "Academic Programme Enquiry",
            "Scholarship & Financial Aid Enquiry",
            "Parent/Guardian Visit",
            "Meeting with Faculty or Staff",
            "Business/Partnership Enquiry",
            "Career & Internship Enquiry",
            "Alumni Visit",
            "Event or Conference Attendance",
            "General Enquiry");
    private static final Path DATA_FILE = Path.of("data", "visitors.xlsx");
    private static final Path STATIC_ROOT = Path.of("src", "main", "resources", "static").toAbsolutePath().normalize();
    private static final Properties LOCAL_SETTINGS = loadLocalSettings();
    private static final String ADMIN_USER = setting("VISITOR_ADMIN_USER", "admin");
    private static final String ADMIN_PASSWORD = setting("VISITOR_ADMIN_PASSWORD", null);
    private static final String DB_URL = setting("DB_URL", "jdbc:postgresql://localhost:5432/visitor_system");
    private static final String DB_USER = setting("DB_USER", "visitor");
    private static final String DB_PASSWORD = setting("DB_PASSWORD", "visitor");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final double CAMPUS_LATITUDE = 5.9204706;
    private static final double CAMPUS_LONGITUDE = 0.0313081;
    private static final double CAMPUS_RADIUS_METERS = 6_000;
    private static final Map<String, String> LOCATION_TOKENS = new LinkedHashMap<>();
    private static final Map<String, CampusLocation> LIVE_LOCATIONS = new LinkedHashMap<>();

    private record Visitor(String id, String name, String contact, String purpose, String email,
                           String residence, String checkedIn, String checkedOut,
                           String verifiedBy, String verifiedAt) {
        Visitor checkedOutAt(String timestamp) {
            return new Visitor(id, name, contact, purpose, email, residence, checkedIn, timestamp, verifiedBy, verifiedAt);
        }

        Visitor verifiedBy(String username, String timestamp) {
            return new Visitor(id, name, contact, purpose, email, residence, checkedIn, checkedOut, username, timestamp);
        }

        List<String> csvFields() {
            return List.of(id, name, contact, purpose, email, residence, checkedIn, checkedOut, verifiedBy, verifiedAt);
        }
    }

    private record CampusLocation(double latitude, double longitude, String updatedAt) {}

    public static void main(String[] args) throws IOException {
        if (ADMIN_PASSWORD == null || ADMIN_PASSWORD.isBlank()) {
            throw new IllegalStateException(
                    "Configure VISITOR_ADMIN_PASSWORD in admin.properties or the environment before starting.");
        }
        initializeDatabase();

        int port = Integer.parseInt(setting("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/visitors", VisitorSystem::checkIn);
        server.createContext("/api/location", VisitorSystem::updateLocation);
        server.createContext("/api/admin/session", VisitorSystem::adminSession);
        server.createContext("/api/admin/checkout", VisitorSystem::adminCheckOut);
        server.createContext("/api/admin/visitors", VisitorSystem::adminVisitors);
        server.createContext("/api/admin/export", VisitorSystem::exportVisitors);
        server.createContext("/api/admin/locations", VisitorSystem::adminLocations);
        server.createContext("/api/admin/verify", VisitorSystem::verifyVisitor);
        server.createContext("/", VisitorSystem::serveStatic);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        server.start();
        System.out.println("Palm Visitor System is running at http://localhost:" + port);
    }

    private static Properties loadLocalSettings() {
        Properties properties = new Properties();
        Path settingsFile = Path.of("admin.properties");
        if (!Files.isRegularFile(settingsFile)) return properties;
        try (Reader reader = Files.newBufferedReader(settingsFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return properties;
        } catch (IOException ex) {
            throw new IllegalStateException("Administrator settings could not be read from admin.properties.", ex);
        }
    }

    private static String setting(String name, String fallback) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) return value;
        value = LOCAL_SETTINGS.getProperty(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void checkIn(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        try {
            Map<String, String> form = readForm(exchange);
            String name = required(form, "name", "Name", 100);
            String contact = required(form, "contact", "Contact number", 40);
            if (!name.matches("[\\p{L}]+(?: +[\\p{L}]+)*")) {
                throw new IllegalArgumentException("Name must contain only letters and spaces.");
            }
            String contactDigits = contact.replaceAll("[^0-9]", "");
            if (!contact.matches("\\+?[0-9](?:[0-9 ()-]*[0-9])?")
                    || contactDigits.length() < 7 || contactDigits.length() > 15) {
                throw new IllegalArgumentException("Phone number must contain 7 to 15 digits; spaces, hyphens, parentheses, and a leading + are allowed.");
            }
            String selectedPurpose = required(form, "purpose", "Purpose of visit", 200);
            String purpose;
            if (selectedPurpose.equals("Other")) {
                purpose = required(form, "otherPurpose", "Please specify your purpose", 200);
            } else if (VISIT_PURPOSES.contains(selectedPurpose)) {
                purpose = selectedPurpose;
            } else {
                sendError(exchange, 400, "Select a valid purpose of visit or enter a purpose for Other.");
                return;
            }
            String email = required(form, "email", "Email", 254);
            String residence = required(form, "residence", "Residence", 200);
            if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                sendError(exchange, 400, "Enter a valid email address.");
                return;
            }

            synchronized (VisitorSystem.class) {
                String id;
                do {
                    id = "VIS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
                } while (readVisitor(id) != null);
                Visitor visitor = new Visitor(id, name, contact, purpose, email, residence,
                        OffsetDateTime.now(ZoneOffset.UTC).format(TIMESTAMP), "", "", "");
                insertVisitor(visitor);
                String trackingToken = "true".equals(form.get("shareLocation"))
                        ? UUID.randomUUID().toString() + UUID.randomUUID()
                        : "";
                if (!trackingToken.isEmpty()) LOCATION_TOKENS.put(id, trackingToken);
                String response = visitorJson(visitor);
                if (!trackingToken.isEmpty()) {
                    response = response.substring(0, response.length() - 1)
                            + ",\"trackingToken\":" + jsonString(trackingToken) + "}";
                }
                sendJson(exchange, 201, response);
            }
        } catch (IllegalArgumentException ex) {
            sendError(exchange, 400, ex.getMessage());
        } catch (IOException ex) {
            sendError(exchange, 500, "The visitor record could not be saved.");
            throw ex;
        }
    }

    private static void adminCheckOut(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        if (!isAdmin(exchange)) {
            sendError(exchange, 401, "Admin sign-in is required.");
            return;
        }
        checkOutVisitor(exchange);
    }

    private static void checkOutVisitor(HttpExchange exchange) throws IOException {
        try {
            String id = readForm(exchange).getOrDefault("visitorId", "").trim();
            if (id.isEmpty()) {
                sendError(exchange, 400, "Enter a Visitor ID.");
                return;
            }
            synchronized (VisitorSystem.class) {
                Visitor visitor = readVisitor(id);
                if (visitor != null) {
                    if (!visitor.checkedOut().isEmpty()) {
                        sendError(exchange, 409, "This visitor has already checked out.");
                        return;
                    }
                    Visitor updated = visitor.checkedOutAt(OffsetDateTime.now(ZoneOffset.UTC).format(TIMESTAMP));
                    updateCheckOut(updated);
                    LOCATION_TOKENS.remove(visitor.id());
                    LIVE_LOCATIONS.remove(visitor.id());
                    sendJson(exchange, 200, visitorJson(updated));
                    return;
                }
            }
            sendError(exchange, 404, "No active visit was found for that Visitor ID.");
        } catch (IllegalArgumentException ex) {
            sendError(exchange, 400, ex.getMessage());
        } catch (IOException ex) {
            sendError(exchange, 500, "The check-out time could not be saved.");
            throw ex;
        }
    }

    private static void adminSession(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;
        if (!isAdmin(exchange)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"Palm Visitor Admin\", charset=\"UTF-8\"");
            sendError(exchange, 401, "Admin sign-in is required.");
            return;
        }
        sendJson(exchange, 200, "{\"username\":" + jsonString(ADMIN_USER) + "}");
    }

    private static void adminVisitors(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;
        if (!isAdmin(exchange)) {
            sendError(exchange, 401, "Admin sign-in is required.");
            return;
        }
        Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
        String search = query.getOrDefault("q", "").trim().toLowerCase(Locale.ROOT);
        synchronized (VisitorSystem.class) {
            List<Visitor> visitors = readVisitors();
            StringBuilder result = new StringBuilder("{\"visitors\":[");
            boolean first = true;
            for (Visitor visitor : visitors) {
                String searchable = String.join(" ", visitor.csvFields()).toLowerCase(Locale.ROOT);
                if (!search.isEmpty() && !searchable.contains(search)) continue;
                if (!first) result.append(',');
                first = false;
                result.append(visitorJson(visitor));
            }
            result.append("],\"total\":").append(visitors.size()).append('}');
            sendJson(exchange, 200, result.toString());
        }
    }

    private static void updateLocation(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        try {
            Map<String, String> form = readForm(exchange);
            String visitorId = required(form, "visitorId", "Visitor ID", 40);
            String trackingToken = required(form, "trackingToken", "Location permission", 100);
            synchronized (VisitorSystem.class) {
                List<Visitor> visitors = readVisitors();
                Visitor activeVisitor = visitors.stream()
                        .filter(visitor -> visitor.id().equalsIgnoreCase(visitorId) && visitor.checkedOut().isEmpty())
                        .findFirst().orElse(null);
                String expectedToken = LOCATION_TOKENS.get(visitorId.toUpperCase(Locale.ROOT));
                if (activeVisitor == null || expectedToken == null
                        || !MessageDigest.isEqual(expectedToken.getBytes(StandardCharsets.UTF_8),
                        trackingToken.getBytes(StandardCharsets.UTF_8))) {
                    sendError(exchange, 403, "Location sharing is not active for this visitor.");
                    return;
                }
                String key = activeVisitor.id().toUpperCase(Locale.ROOT);
                if ("true".equals(form.get("stop"))) {
                    LOCATION_TOKENS.remove(key);
                    LIVE_LOCATIONS.remove(key);
                    sendJson(exchange, 200, "{\"stopped\":true}");
                    return;
                }
                double latitude = Double.parseDouble(required(form, "latitude", "Latitude", 32));
                double longitude = Double.parseDouble(required(form, "longitude", "Longitude", 32));
                if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                        || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
                    throw new IllegalArgumentException("The location coordinates are invalid.");
                }
                if (distanceFromCampus(latitude, longitude) > CAMPUS_RADIUS_METERS) {
                    sendError(exchange, 403, "Location updates are only available within 6 km of the Palm campus.");
                    return;
                }
                LIVE_LOCATIONS.put(key, new CampusLocation(latitude, longitude,
                        OffsetDateTime.now(ZoneOffset.UTC).format(TIMESTAMP)));
                sendJson(exchange, 200, "{\"updated\":true}");
            }
        } catch (IllegalArgumentException ex) {
            sendError(exchange, 400, ex.getMessage());
        } catch (IOException ex) {
            sendError(exchange, 500, "The location update could not be recorded.");
            throw ex;
        }
    }

    private static void adminLocations(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;
        if (!isAdmin(exchange)) {
            sendError(exchange, 401, "Admin sign-in is required.");
            return;
        }
        synchronized (VisitorSystem.class) {
            List<Visitor> visitors = readVisitors();
            OffsetDateTime expiry = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2);
            LIVE_LOCATIONS.entrySet().removeIf(entry ->
                    OffsetDateTime.parse(entry.getValue().updatedAt()).isBefore(expiry));
            StringBuilder result = new StringBuilder("{\"locations\":[");
            boolean first = true;
            for (Map.Entry<String, CampusLocation> entry : LIVE_LOCATIONS.entrySet()) {
                Visitor visitor = visitors.stream()
                        .filter(item -> item.id().equalsIgnoreCase(entry.getKey()) && item.checkedOut().isEmpty())
                        .findFirst().orElse(null);
                if (visitor == null) continue;
                if (!first) result.append(',');
                first = false;
                CampusLocation location = entry.getValue();
                result.append("{\"visitorId\":").append(jsonString(visitor.id()))
                        .append(",\"name\":").append(jsonString(visitor.name()))
                        .append(",\"latitude\":").append(location.latitude())
                        .append(",\"longitude\":").append(location.longitude())
                        .append(",\"updatedAt\":").append(jsonString(location.updatedAt())).append('}');
            }
            result.append("]}");
            sendJson(exchange, 200, result.toString());
        }
    }

    private static void verifyVisitor(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        if (!isAdmin(exchange)) {
            sendError(exchange, 401, "Admin sign-in is required.");
            return;
        }
        try {
            Map<String, String> form = readForm(exchange);
            String visitorId = required(form, "visitorId", "Visitor ID", 40);
            if (!"true".equals(form.get("physicalIdMatched"))) {
                sendError(exchange, 400, "Confirm that the photo ID was checked in person.");
                return;
            }
            synchronized (VisitorSystem.class) {
                Visitor visitor = readVisitor(visitorId);
                if (visitor != null) {
                    if (!visitor.checkedOut().isEmpty()) {
                        sendError(exchange, 409, "This visitor has already checked out.");
                        return;
                    }
                    Visitor verified = visitor.verifiedBy(ADMIN_USER,
                            OffsetDateTime.now(ZoneOffset.UTC).format(TIMESTAMP));
                    updateVerification(verified);
                    sendJson(exchange, 200, visitorJson(verified));
                    return;
                }
            }
            sendError(exchange, 404, "No active visit was found for that Visitor ID.");
        } catch (IllegalArgumentException ex) {
            sendError(exchange, 400, ex.getMessage());
        } catch (IOException ex) {
            sendError(exchange, 500, "The ID verification could not be saved.");
            throw ex;
        }
    }

    private static double distanceFromCampus(double latitude, double longitude) {
        double earthRadius = 6_371_000;
        double latitudeDelta = Math.toRadians(latitude - CAMPUS_LATITUDE);
        double longitudeDelta = Math.toRadians(longitude - CAMPUS_LONGITUDE);
        double haversine = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
                + Math.cos(Math.toRadians(CAMPUS_LATITUDE)) * Math.cos(Math.toRadians(latitude))
                * Math.sin(longitudeDelta / 2) * Math.sin(longitudeDelta / 2);
        double boundedHaversine = Math.min(1, Math.max(0, haversine));
        return earthRadius * 2 * Math.atan2(Math.sqrt(boundedHaversine), Math.sqrt(1 - boundedHaversine));
    }

    private static void exportVisitors(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;
        if (!isAdmin(exchange)) {
            sendError(exchange, 401, "Admin sign-in is required.");
            return;
        }
        synchronized (VisitorSystem.class) {
            byte[] csv = csvContents(readVisitors()).getBytes(StandardCharsets.UTF_8);
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/csv; charset=utf-8");
            headers.set("Content-Disposition", "attachment; filename=\"palm-visitor-records.csv\"");
            headers.set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, csv.length);
            exchange.getResponseBody().write(csv);
            exchange.close();
        }
    }

    private static boolean isAdmin(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Basic ")) return false;
        try {
            String credentials = new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
            int separator = credentials.indexOf(':');
            if (separator < 0) return false;
            byte[] expectedUser = ADMIN_USER.getBytes(StandardCharsets.UTF_8);
            byte[] actualUser = credentials.substring(0, separator).getBytes(StandardCharsets.UTF_8);
            byte[] expectedPassword = ADMIN_PASSWORD.getBytes(StandardCharsets.UTF_8);
            byte[] actualPassword = credentials.substring(separator + 1).getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expectedUser, actualUser)
                    && MessageDigest.isEqual(expectedPassword, actualPassword);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static void serveStatic(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/") || path.equals("/admin") || path.equals("/admin/")) path = "/index.html";
        Path requested = STATIC_ROOT.resolve(path.substring(1)).normalize();
        if (!requested.startsWith(STATIC_ROOT) || !Files.isRegularFile(requested)) {
            sendError(exchange, 404, "Page not found.");
            return;
        }
        String type = switch (requested.getFileName().toString().toLowerCase()) {
            case "index.html" -> "text/html; charset=utf-8";
            case "app.js" -> "text/javascript; charset=utf-8";
            case "styles.css" -> "text/css; charset=utf-8";
            case "puc-logo.png" -> "image/png";
            case "campus-photo.jpeg" -> "image/jpeg";
            default -> "application/octet-stream";
        };
        byte[] content = Files.readAllBytes(requested);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, content.length);
        exchange.getResponseBody().write(content);
        exchange.close();
    }

    private static boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (exchange.getRequestMethod().equalsIgnoreCase(method)) return true;
        exchange.getResponseHeaders().set("Allow", method);
        sendError(exchange, 405, "This request method is not supported.");
        return false;
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 16_384) throw new IllegalArgumentException("The submitted form is too large.");
                output.write(buffer, 0, count);
            }
            return parseForm(output.toString(StandardCharsets.UTF_8));
        }
    }

    private static Map<String, String> parseForm(String encoded) {
        Map<String, String> values = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) return values;
        for (String pair : encoded.split("&")) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            values.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return values;
    }

    private static String required(Map<String, String> form, String key, String label, int maxLength) {
        String value = form.getOrDefault(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(label + " is required.");
        if (value.length() > maxLength) throw new IllegalArgumentException(label + " must be " + maxLength + " characters or fewer.");
        return value;
    }

    private static List<Visitor> readVisitors() throws IOException {
        List<Visitor> visitors = new ArrayList<>();
        try (Connection connection = openDatabase();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT visitor_id, name, contact, purpose, email, residence, checked_in, checked_out, verified_by, verified_at "
                             + "FROM visitors ORDER BY checked_in DESC");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) visitors.add(visitorFromResult(result));
            return visitors;
        } catch (SQLException ex) {
            throw new IOException("The visitor records could not be read from PostgreSQL.", ex);
        }
    }

    private static Visitor readVisitor(String id) throws IOException {
        try (Connection connection = openDatabase();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT visitor_id, name, contact, purpose, email, residence, checked_in, checked_out, verified_by, verified_at "
                             + "FROM visitors WHERE LOWER(visitor_id) = LOWER(?)")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? visitorFromResult(result) : null;
            }
        } catch (SQLException ex) {
            throw new IOException("The visitor record could not be read from PostgreSQL.", ex);
        }
    }

    private static Visitor visitorFromResult(ResultSet result) throws SQLException {
        return new Visitor(
                result.getString("visitor_id"), result.getString("name"), result.getString("contact"),
                result.getString("purpose"), result.getString("email"), result.getString("residence"),
                result.getString("checked_in"), result.getString("checked_out"),
                result.getString("verified_by"), result.getString("verified_at"));
    }

    private static Connection openDatabase() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }

    private static void initializeDatabase() throws IOException {
        String schema = """
                CREATE TABLE IF NOT EXISTS visitors (
                    visitor_id VARCHAR(40) PRIMARY KEY,
                    name VARCHAR(100) NOT NULL,
                    contact VARCHAR(40) NOT NULL,
                    purpose VARCHAR(200) NOT NULL,
                    email VARCHAR(254) NOT NULL,
                    residence VARCHAR(200) NOT NULL,
                    checked_in TEXT NOT NULL,
                    checked_out TEXT NOT NULL DEFAULT '',
                    verified_by VARCHAR(100) NOT NULL DEFAULT '',
                    verified_at TEXT NOT NULL DEFAULT ''
                )
                """;
        try (Connection connection = openDatabase();
             Statement statement = connection.createStatement()) {
            statement.execute(schema);
        } catch (SQLException ex) {
            throw new IOException("PostgreSQL could not be reached or initialized.", ex);
        }
        migrateExcelRecords();
    }

    private static void migrateExcelRecords() throws IOException {
        if (!Files.exists(DATA_FILE)) return;
        try (ZipFile workbook = new ZipFile(DATA_FILE.toFile())) {
            ZipEntry sheet = workbook.getEntry("xl/worksheets/sheet1.xml");
            if (sheet == null) throw new IOException("The visitor workbook is missing its first worksheet.");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            try (InputStream sheetContents = workbook.getInputStream(sheet);
                 Connection connection = openDatabase()) {
                NodeList rows = factory.newDocumentBuilder().parse(sheetContents)
                        .getElementsByTagNameNS("*", "row");
                String insert = """
                        INSERT INTO visitors (
                            visitor_id, name, contact, purpose, email, residence,
                            checked_in, checked_out, verified_by, verified_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (visitor_id) DO NOTHING
                        """;
                try (PreparedStatement statement = connection.prepareStatement(insert)) {
                    for (int i = 1; i < rows.getLength(); i++) {
                        NodeList cells = ((Element) rows.item(i)).getElementsByTagNameNS("*", "c");
                        if (cells.getLength() < 8) continue;
                        List<String> values = new ArrayList<>(CSV_HEADER.size());
                        for (int cell = 0; cell < CSV_HEADER.size(); cell++) {
                            if (cell >= cells.getLength()) {
                                values.add("");
                                continue;
                            }
                            NodeList text = ((Element) cells.item(cell)).getElementsByTagNameNS("*", "t");
                            values.add(text.getLength() == 0 ? "" : text.item(0).getTextContent());
                        }
                        Visitor visitor = new Visitor(values.get(0), values.get(1), values.get(2),
                                values.get(3), values.get(4), values.get(5), values.get(6),
                                values.get(7), values.get(8), values.get(9));
                        setVisitor(statement, visitor);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
            }
        } catch (javax.xml.parsers.ParserConfigurationException | SAXException | SQLException ex) {
            throw new IOException("The existing visitor workbook could not be migrated.", ex);
        }
    }

    private static void insertVisitor(Visitor visitor) throws IOException {
        String sql = """
                INSERT INTO visitors (
                    visitor_id, name, contact, purpose, email, residence,
                    checked_in, checked_out, verified_by, verified_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = openDatabase();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            setVisitor(statement, visitor);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IOException("The visitor record could not be saved to PostgreSQL.", ex);
        }
    }

    private static void updateCheckOut(Visitor visitor) throws IOException {
        try (Connection connection = openDatabase();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE visitors SET checked_out = ? WHERE LOWER(visitor_id) = LOWER(?)")) {
            statement.setString(1, visitor.checkedOut());
            statement.setString(2, visitor.id());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IOException("The check-out time could not be saved to PostgreSQL.", ex);
        }
    }

    private static void updateVerification(Visitor visitor) throws IOException {
        try (Connection connection = openDatabase();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE visitors SET verified_by = ?, verified_at = ? WHERE LOWER(visitor_id) = LOWER(?)")) {
            statement.setString(1, visitor.verifiedBy());
            statement.setString(2, visitor.verifiedAt());
            statement.setString(3, visitor.id());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IOException("The ID verification could not be saved to PostgreSQL.", ex);
        }
    }

    private static void setVisitor(PreparedStatement statement, Visitor visitor) throws SQLException {
        statement.setString(1, visitor.id());
        statement.setString(2, visitor.name());
        statement.setString(3, visitor.contact());
        statement.setString(4, visitor.purpose());
        statement.setString(5, visitor.email());
        statement.setString(6, visitor.residence());
        statement.setString(7, visitor.checkedIn());
        statement.setString(8, visitor.checkedOut());
        statement.setString(9, visitor.verifiedBy());
        statement.setString(10, visitor.verifiedAt());
    }

    private static String csvContents(List<Visitor> visitors) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        appendCsvRow(csv, CSV_HEADER);
        for (Visitor visitor : visitors) appendCsvRow(csv, visitor.csvFields());
        return csv.toString();
    }

    private static void appendCsvRow(StringBuilder output, List<String> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) output.append(',');
            String field = csvSafeValue(fields.get(i));
            output.append('"').append(field.replace("\"", "\"\"")).append('"');
        }
        output.append("\r\n");
    }

    private static String csvSafeValue(String value) {
        int firstNonWhitespace = 0;
        while (firstNonWhitespace < value.length() && Character.isWhitespace(value.charAt(firstNonWhitespace))) {
            firstNonWhitespace++;
        }
        if (firstNonWhitespace < value.length() && "=+-@".indexOf(value.charAt(firstNonWhitespace)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    private static String visitorJson(Visitor visitor) {
        return "{\"visitorId\":" + jsonString(visitor.id())
                + ",\"name\":" + jsonString(visitor.name())
                + ",\"contact\":" + jsonString(visitor.contact())
                + ",\"purpose\":" + jsonString(visitor.purpose())
                + ",\"email\":" + jsonString(visitor.email())
                + ",\"residence\":" + jsonString(visitor.residence())
                + ",\"checkedIn\":" + jsonString(visitor.checkedIn())
                + ",\"checkedOut\":" + jsonString(visitor.checkedOut())
                + ",\"verifiedBy\":" + jsonString(visitor.verifiedBy())
                + ",\"verifiedAt\":" + jsonString(visitor.verifiedAt()) + "}";
    }

    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", body);
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, "{\"error\":" + jsonString(message) + "}");
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
