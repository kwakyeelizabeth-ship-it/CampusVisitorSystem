
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Visitor {
    private String visitorId;
    private String fullName;
    private String phone;
    private String purpose;
    private String hostName;
    private LocalDateTime checkInTime;
    private LocalDateTime checkOutTime;

    // Constructor for new check-ins
    public Visitor(String visitorId, String fullName, String phone, String purpose, String hostName) {
        this.visitorId = visitorId;
        this.fullName = fullName;
        this.phone = phone;
        this.purpose = purpose;
        this.hostName = hostName;
        this.checkInTime = LocalDateTime.now(); // Captures exact check-in time automatically
        this.checkOutTime = null;
    }

    // Constructor used when reading existing records from a file
    public Visitor(String visitorId, String fullName, String phone, String purpose, String hostName, String checkInTime, String checkOutTime) {
        this.visitorId = visitorId;
        this.fullName = fullName;
        this.phone = phone;
        this.purpose = purpose;
        this.hostName = hostName;
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        this.checkInTime = checkInTime.equals("null") ? null : LocalDateTime.parse(checkInTime, formatter);
        this.checkOutTime = (checkOutTime.equals("null") || checkOutTime.isEmpty()) ? null : LocalDateTime.parse(checkOutTime, formatter);
    }

    // Getters and Setters
    public String getVisitorId() { return visitorId; }
    public String getFullName() { return fullName; }
    public String getPhone() { return phone; }
    public String getPurpose() { return purpose; }
    public String getHostName() { return hostName; }
    public LocalDateTime getCheckInTime() { return checkInTime; }
    public LocalDateTime getCheckOutTime() { return checkOutTime; }
    
    public void setCheckOutTime(LocalDateTime checkOutTime) {
        this.checkOutTime = checkOutTime;
    }

    // Format data nicely to save into a text/CSV file
    public String toFileString() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String inStr = (checkInTime != null) ? checkInTime.format(formatter) : "null";
        String outStr = (checkOutTime != null) ? checkOutTime.format(formatter) : "null";
        return visitorId + "," + fullName + "," + phone + "," + purpose + "," + hostName + "," + inStr + "," + outStr;
    }
}