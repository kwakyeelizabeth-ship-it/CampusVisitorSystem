FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY src ./src
COPY lib ./lib
RUN mkdir -p out && javac --add-modules jdk.httpserver -encoding UTF-8 -cp "lib/*" -d out src/main/java/VisitorSystem.java
CMD ["java", "--add-modules", "jdk.httpserver", "-cp", "out:lib/*", "VisitorSystem"]