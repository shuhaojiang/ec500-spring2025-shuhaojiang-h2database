/*
 * Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.build.doc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.h2.dev.ftp.FtpClient;
import org.h2.engine.Constants;
import org.h2.store.fs.FileUtils;
import org.h2.test.utils.OutputCatcher;
import org.h2.util.ScriptReader;
import org.h2.util.StringUtils;

/**
 * Upload the code coverage result to the H2 web site.
 */
public class UploadBuild {

    /**
     * This method is called when executing this application from the command
     * line.
     *
     * @param args the command line parameters
     */
    public static void main(String... args) throws Exception {
        System.exit(0);
        System.setProperty("h2.socketConnectTimeout", "30000");
        String password = System.getProperty("h2.ftpPassword");
        if (password == null) {
            return;
        }
        FtpClient ftp = FtpClient.open("h2database.com");
        ftp.login("h2database", password);
        ftp.changeWorkingDirectory("/httpdocs");
        Path coverageFile = Paths.get("coverage/index.html");
        boolean coverage = Files.exists(coverageFile);
        boolean coverageFailed;
        if (coverage) {
            String index = new String(Files.readAllBytes(coverageFile), StandardCharsets.ISO_8859_1);
            coverageFailed = index.contains("CLASS=\"h\"");
            while (true) {
                int idx = index.indexOf("<A HREF=\"");
                if (idx < 0) {
                    break;
                }
                int end = index.indexOf('>', idx) + 1;
                index = index.substring(0, idx) + index.substring(end);
                idx = index.indexOf("</A>");
                index = index.substring(0, idx) + index.substring(idx + "</A>".length());
            }
            index = StringUtils.replaceAll(index, "[all", "");
            index = StringUtils.replaceAll(index, "classes]", "");
            Files.write(Paths.get("coverage/overview.html"), index.getBytes(StandardCharsets.ISO_8859_1));
            Files.createDirectories(Paths.get("details"));
            zip("details/coverage_files.zip", "coverage", true);
            zip("coverage.zip", "details", false);
            FileUtils.delete("coverage.txt");
            FileUtils.delete("details/coverage_files.zip");
            FileUtils.delete("details");
            if (ftp.exists("/httpdocs", "coverage")) {
                ftp.removeDirectoryRecursive("/httpdocs/coverage");
            }
            ftp.makeDirectory("/httpdocs/coverage");
        } else {
            coverageFailed = true;
        }
        String testOutput;
        boolean error;
        Path testOutputFile = Paths.get("docs/html/testOutput.html");
        if (Files.exists(testOutputFile)) {
            testOutput = new String(Files.readAllBytes(testOutputFile), StandardCharsets.UTF_8);
            error = testOutput.contains(OutputCatcher.START_ERROR);
        } else {
            Path logFile = Paths.get("log.txt");
            if (Files.exists(logFile)) {
                testOutput = new String(Files.readAllBytes(logFile));
                testOutput = testOutput.replaceAll("\n", "<br />");
                error = true;
            } else {
                testOutput = "No log.txt";
                error = true;
            }
        }
        if (!ftp.exists("/httpdocs", "automated")) {
            ftp.makeDirectory("/httpdocs/automated");
        }
        String buildSql;
        if (ftp.exists("/httpdocs/automated", "history.sql")) {
            buildSql = new String(ftp.retrieve("/httpdocs/automated/history.sql"));
        } else {
            buildSql = "create table item(id identity, title varchar, " +
                    "issued timestamp, desc varchar);\n";
        }
        String ts = new java.sql.Timestamp(System.currentTimeMillis()).toString();
        String now = ts.substring(0, 16);
        int idx = testOutput.indexOf("Statements per second: ");
        if (idx >= 0) {
            int end = testOutput.indexOf("<br />", idx);
            if (end >= 0) {
                String result = testOutput.substring(idx +
                        "Statements per second: ".length(), end);
                now += " " + result + " op/s";
            }
        }
        String sql = "insert into item(title, issued, desc) values('Build " +
            now +
            (error ? " FAILED" : "") +
            (coverageFailed ? " COVERAGE" : "") +
            "', '" + ts +
            "', '<a href=\"https://h2database.com/" +
            "html/testOutput.html\">Output</a>" +
            " - <a href=\"https://h2database.com/" +
            "coverage/overview.html\">Coverage</a>" +
            " - <a href=\"https://h2database.com/" +
            "automated/h2-latest.jar\">Jar</a>');\n";
        buildSql += sql;
        Connection conn;
        try {
            Class.forName("org.h2.Driver");
            conn = DriverManager.getConnection("jdbc:h2:mem:");
        } catch (Exception e) {
            Class.forName("org.h2.upgrade.v1_1.Driver");
            conn = DriverManager.getConnection("jdbc:h2v1_1:mem:");
        }
        conn.createStatement().execute(buildSql);
        String newsfeed = new String(Files.readAllBytes(Paths.get("src/tools/org/h2/build/doc/buildNewsfeed.sql")),
                StandardCharsets.UTF_8);
        ScriptReader r = new ScriptReader(new StringReader(newsfeed));
        Statement stat = conn.createStatement();
        ResultSet rs = null;
        while (true) {
            String s = r.readStatement();
            if (s == null) {
                break;
            }
            if (stat.execute(s)) {
                rs = stat.getResultSet();
            }
        }
        rs.next();
        String content = rs.getString("content");
        conn.close();
        ftp.store("/httpdocs/automated/history.sql",
                new ByteArrayInputStream(buildSql.getBytes()));
        ftp.store("/httpdocs/automated/news.xml",
                new ByteArrayInputStream(content.getBytes()));
        ftp.store("/httpdocs/html/testOutput.html",
                new ByteArrayInputStream(testOutput.getBytes()));
        String jarFileName = "bin/h2-" + Constants.VERSION + ".jar";
        if (FileUtils.exists(jarFileName)) {
            ftp.store("/httpdocs/automated/h2-latest.jar",
                    Files.newInputStream(Paths.get(jarFileName)));
        }
        if (coverage) {
            ftp.store("/httpdocs/coverage/overview.html",
                    Files.newInputStream(Paths.get("coverage/overview.html")));
            ftp.store("/httpdocs/coverage/coverage.zip",
                    Files.newInputStream(Paths.get("coverage.zip")));
            FileUtils.delete("coverage.zip");
        }
        String mavenRepoDir = System.getProperty("user.home") + "/.m2/repository/";
        boolean mavenSnapshot = Files.exists(Paths.get(mavenRepoDir +
                "com/h2database/h2/1.0-SNAPSHOT/h2-1.0-SNAPSHOT.jar"));
        if (mavenSnapshot) {
            if (!ftp.exists("/httpdocs", "m2-repo")) {
                ftp.makeDirectory("/httpdocs/m2-repo");
            }
            if (!ftp.exists("/httpdocs/m2-repo", "com")) {
                ftp.makeDirectory("/httpdocs/m2-repo/com");
            }
            if (!ftp.exists("/httpdocs/m2-repo/com", "h2database")) {
                ftp.makeDirectory("/httpdocs/m2-repo/com/h2database");
            }
            if (!ftp.exists("/httpdocs/m2-repo/com/h2database", "h2")) {
                ftp.makeDirectory("/httpdocs/m2-repo/com/h2database/h2");
            }
            if (!ftp.exists("/httpdocs/m2-repo/com/h2database/h2", "1.0-SNAPSHOT")) {
                ftp.makeDirectory("/httpdocs/m2-repo/com/h2database/h2/1.0-SNAPSHOT");
            }
            if (!ftp.exists("/httpdocs/m2-repo/com/h2database", "h2-mvstore")) {
                ftp.makeDirectory("/httpdocs/m2-repo/com/h2database/h2-mvstore");
            }
            if (!ftp.exists("/httpdocs/m2-repo/com/h2database/h2-mvstore", "1.0-SNAPSHOT")) {
                ftp.makeDirectory("/httpdocs/m2-repo/com/h2database/h2-mvstore/1.0-SNAPSHOT");
            }
            ftp.store("/httpdocs/m2-repo/com/h2database/h2" +
                    "/1.0-SNAPSHOT/h2-1.0-SNAPSHOT.pom",
                    Files.newInputStream(Paths.get(mavenRepoDir +
                            "com/h2database/h2/1.0-SNAPSHOT/h2-1.0-SNAPSHOT.pom")));
            ftp.store("/httpdocs/m2-repo/com/h2database/h2" +
                            "/1.0-SNAPSHOT/h2-1.0-SNAPSHOT.jar",
                    Files.newInputStream(Paths.get(mavenRepoDir +
                            "com/h2database/h2/1.0-SNAPSHOT/h2-1.0-SNAPSHOT.jar")));
            ftp.store("/httpdocs/m2-repo/com/h2database/h2-mvstore" +
                            "/1.0-SNAPSHOT/h2-mvstore-1.0-SNAPSHOT.pom",
                    Files.newInputStream(Paths.get(mavenRepoDir +
                            "com/h2database/h2-mvstore/1.0-SNAPSHOT/h2-mvstore-1.0-SNAPSHOT.pom")));
            ftp.store("/httpdocs/m2-repo/com/h2database/h2-mvstore" +
                            "/1.0-SNAPSHOT/h2-mvstore-1.0-SNAPSHOT.jar",
                    Files.newInputStream(Paths.get(mavenRepoDir +
                            "com/h2database/h2-mvstore/1.0-SNAPSHOT/h2-mvstore-1.0-SNAPSHOT.jar")));
        }
        ftp.close();
    }

    private static void zip(String destFile, String directory, boolean storeOnly) throws IOException {
        ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(Paths.get(destFile)));
        if (storeOnly) {
            zipOut.setMethod(ZipOutputStream.STORED);
        }
        zipOut.setLevel(Deflater.BEST_COMPRESSION);
        Path base = Paths.get(directory);
        Files.walkFileTree(base, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                byte[] data = Files.readAllBytes(file);
                ZipEntry entry = new ZipEntry(base.relativize(file).toString().replace('\\', '/'));
                CRC32 crc = new CRC32();
                crc.update(data);
                entry.setSize(data.length);
                entry.setCrc(crc.getValue());
                zipOut.putNextEntry(entry);
                zipOut.write(data);
                zipOut.closeEntry();
                return FileVisitResult.CONTINUE;
            }
        });
        zipOut.finish();
        zipOut.close();
    }

}
