import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class VisitorFileManager {
    private static final String FILE_NAME = "visitors.txt";

    // Save a single visitor record to the text file (Appends to the file)
    public static void saveVisitor(Visitor visitor) {
        try (FileWriter fw = new FileWriter(FILE_NAME, true);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter out = new PrintWriter(bw)) {
            out.println(visitor.toFileString());
        } catch (IOException e) {
            System.out.println("Error saving visitor: " + e.getMessage());
        }
    }

    // Read all visitor records from the text file
    public static List<Visitor> getAllVisitors() {
        List<Visitor> visitors = new ArrayList<>();
        File file = new File(FILE_NAME);
        if (!file.exists()) {
            return visitors; // Return an empty list if the file hasn't been created yet
        }

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 7) {
                    Visitor v = new Visitor(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5], parts[6]);
                    visitors.add(v);
                }
            }
        } catch (IOException e) {
            System.out.println("Error reading visitors: " + e.getMessage());
        }
        return visitors;
    }
    
    // Automatically generates a sequential unique ID based on existing records
    public static String generateVisitorId() {
        List<Visitor> existing = getAllVisitors();
        int nextId = existing.size() + 1;
        return String.format("VIS-%03d", nextId);
    }
}