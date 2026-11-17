FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY src ./src
RUN mkdir -p out && javac --add-modules jdk.httpserver -encoding UTF-8 -d out src/main/java/VisitorSystem.java
CMD ["java", "--add-modules", "jdk.httpserver", "-cp", "out", "VisitorSystem"]