# Palm University College Visitor System

A Java 17 visitor check-in application with an HTML/CSS/JavaScript interface. Visitor records are stored in PostgreSQL. When PostgreSQL is initialized, existing records in `data\visitors.xlsx` are migrated automatically. The admin portal can search records and export the complete ledger as an Excel-compatible CSV file.

## PostgreSQL setup

The included Docker Compose configuration starts PostgreSQL and the application:

```bat
docker compose up --build
```

The database is available on port `5432`. Data is persisted in the `visitor_postgres_data` Docker volume.

For a local Java run, start PostgreSQL and configure these environment variables (or add them to `admin.properties`):

```properties
DB_URL=jdbc:postgresql://localhost:5432/visitor_system
DB_USER=visitor
DB_PASSWORD=change-this-password
```

## Run on Windows

1. Install a Java 17 or newer JDK.
2. Double-click `run.bat`, or run it from PowerShell.
3. Open [http://localhost:8080](http://localhost:8080).

The first run creates the `data` folder and initializes the visitor spreadsheet.

## Administrator

The staff sign-in is not linked from the visitor landing page. Authorized staff can open [http://localhost:8080/admin](http://localhost:8080/admin). Admin actions and visitor records remain protected by server-side authentication.

Before starting the application, create a local `admin.properties` file in this folder:

```properties
VISITOR_ADMIN_USER=your-staff-username
VISITOR_ADMIN_PASSWORD=replace-with-a-private-password
```

`admin.properties` is excluded by `.gitignore`, so credentials stay local. Alternatively, set `VISITOR_ADMIN_USER` and `VISITOR_ADMIN_PASSWORD` as environment variables. There is no default admin password, and the application will not start until a password is configured. Set `PORT` to change the listening port.

## Visitor records

Each check-in creates a unique visitor ID and records the check-in time in UTC. Visitors select a purpose from the check-in form; choosing **Other** reveals a required field for a custom purpose. Visitors cannot check themselves out; reception staff check them out from the admin visitor table, and the check-out time is saved to the same record. Administrators can search across visitor details and download the complete Excel-compatible CSV ledger. The `.xlsx` workbook is managed by the Java application and can also be opened in Excel.

The security desk can look up an active visitor ID, compare the visitor's photo ID in person, and record the check in the visitor workbook. Visitors may optionally consent to location sharing before check-in. The browser asks for location permission only after a successful check-in; location updates are accepted within 6 kilometres of the Palm University College campus pin, including off-site, are visible only to signed-in staff, are held in memory (not the workbook), and are removed on stop, check-out, or leaving the 6-kilometre range. Temporary network or server errors are retried on subsequent location updates. Inactive location updates expire after two minutes. Geolocation requires HTTPS outside localhost.

The Palm University College logo in `src\main\resources\static\assets\puc-logo.png` is sourced from the [official Palm University College website](https://palm.edu.gh/). The campus photo is from the [SchoolFinder Palm University College page](https://schoolfinder.tortoisepath.com/school/palm-university-college/).

This lightweight application is intended for a trusted campus network. Configure administrator credentials before use and deploy behind HTTPS before exposing it beyond localhost.
