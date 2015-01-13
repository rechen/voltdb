/* This file is part of VoltDB.
 * Copyright (C) 2008-2015 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb.compiler;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import junit.framework.TestCase;

import org.apache.commons.lang3.StringUtils;
import org.voltdb.ProcInfoData;
import org.voltdb.VoltDB.Configuration;
import org.voltdb.VoltType;
import org.voltdb.benchmark.tpcc.TPCCProjectBuilder;
import org.voltdb.catalog.Catalog;
import org.voltdb.catalog.CatalogMap;
import org.voltdb.catalog.Column;
import org.voltdb.catalog.Connector;
import org.voltdb.catalog.ConnectorTableInfo;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Group;
import org.voltdb.catalog.GroupRef;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.SnapshotSchedule;
import org.voltdb.catalog.Statement;
import org.voltdb.catalog.Table;
import org.voltdb.compiler.VoltCompiler.Feedback;
import org.voltdb.compiler.VoltCompiler.VoltCompilerException;
import org.voltdb.types.IndexType;
import org.voltdb.utils.BuildDirectoryUtils;
import org.voltdb.utils.CatalogUtil;
import org.voltdb.utils.MiscUtils;

public class TestVoltCompiler extends TestCase {

    String nothing_jar;
    String testout_jar;

    @Override
    public void setUp() {
        nothing_jar = BuildDirectoryUtils.getBuildDirectoryPath() + File.pathSeparator + "nothing.jar";
        testout_jar = BuildDirectoryUtils.getBuildDirectoryPath() + File.pathSeparator + "testout.jar";
    }

    @Override
    public void tearDown() {
        File njar = new File(nothing_jar);
        njar.delete();
        File tjar = new File(testout_jar);
        tjar.delete();
    }

    public static VoltCompiler checkDDLErrorMessage(String jar_out,
            String literalSchema, String expectedError) {
        final VoltCompiler compiler = new VoltCompiler();
        boolean success = compileFromLiteralSchema(jar_out, compiler, literalSchema);
        if (expectedError == null) {
            assertTrue("Expected no compilation errors but got these:\n" +
                    feedbackToString(compiler.m_errors), success);
        } else {
            assertFalse("Expected failure but got success", success);
            if ( ! isFeedbackPresent(expectedError, compiler.m_errors)) {
                fail("Expected text (" + expectedError + ") not found in output:\n" +
                        feedbackToString(compiler.m_errors));
            }
        }
        return compiler;
    }

    public static VoltCompiler checkDDLWarningMessage(String jar_out,
            String literalSchema, String expectedWarning) {
        assert(expectedWarning != null);
        final VoltCompiler compiler = new VoltCompiler();
        boolean success = compileFromLiteralSchema(jar_out, compiler, literalSchema);
        assertTrue("Expected only a warning but got error(s):\n" +
                feedbackToString(compiler.m_errors), success);
        if ( ! isFeedbackPresent(expectedWarning, compiler.m_warnings)) {
            fail("Expected text (" + expectedWarning + ") not found in warning output:\n" +
                    feedbackToString(compiler.m_warnings));
        }
        return compiler;
    }

    private VoltCompiler checkDDLErrorMessage(String literalSchema, String errorMsg) {
        return checkDDLErrorMessage(testout_jar, literalSchema, errorMsg);
    }

    private void checkValidUniqueAndAssumeUnique(String ddl, String errorUnique, String errorAssumeUnique) {
        checkDDLErrorMessage(ddl, errorUnique);
        checkDDLErrorMessage(ddl.replace("UNIQUE", "ASSUMEUNIQUE"), errorAssumeUnique);
    }

    public void testBrokenLineParsing() {
        final String simpleSchema1 =
            "create table table1r_el  (pkey integer, column2_integer integer, PRIMARY KEY(pkey));\n" +
            "create view v_table1r_el (column2_integer, num_rows) as\n" +
            "select column2_integer as column2_integer,\n" +
                "count(*) as num_rows\n" +
            "from table1r_el\n" +
            "group by column2_integer;\n" +
            "create view v_table1r_el2 (column2_integer, num_rows) as\n" +
            "select column2_integer as column2_integer,\n" +
                "count(*) as num_rows\n" +
            "from table1r_el\n" +
            "group by column2_integer\n;\n";

        final String schemaPath = MiscUtils.writeStringToTempFilePath(simpleSchema1);

        final String simpleProject =
            "<?xml version=\"1.0\"?>\n" +
            "<project>" +
            "<database name='database'>" +
            "<schemas>" +
            "<schema path='" + schemaPath + "' />" +
            "</schemas>" +
            "<procedures>" +
            //TODO: Define this proc in the ddl and eliminate this project.xml.
            "<procedure class='Foo'>" +
            "<sql>select * from table1r_el;</sql>" +
            "</procedure>" +
            "</procedures>" +
            "</database>" +
            "</project>";

        final String projectPath = MiscUtils.writeStringToTempFilePath(simpleProject);

        final VoltCompiler compiler = new VoltCompiler();

        final boolean success = compiler.compileWithProjectXML(projectPath, testout_jar);
        assertTrue(success);
    }

    public void testUTF8XMLFromHSQL() throws IOException {
        CatalogBuilder cb = new CatalogBuilder(
                "create table blah  (pkey integer not null, strval varchar(200), PRIMARY KEY(pkey));\n" +
                "partition table blah on column pkey;\n" +
                "")
        .addStmtProcedure("utf8insert", "insert into blah values(1, 'něco za nic')")
        ;
        assertTrue(cb.compile(Configuration.getPathToCatalogForTest("utf8xml.jar")));
    }

    private static String feedbackToString(List<Feedback> fbs) {
        StringBuilder sb = new StringBuilder();
        for (Feedback fb : fbs) {
            sb.append(fb.toString()).append("\n");
        }
        return sb.toString();
    }

    private static boolean isFeedbackPresent(String expectedError,
            ArrayList<Feedback> fbs) {
        for (Feedback fb : fbs) {
            if (fb.getStandardFeedbackLine().contains(expectedError)) {
                return true;
            }
        }
        return false;
    }

    public void testMismatchedPartitionParams() {
        checkDDLErrorMessage(
                "CREATE TABLE PKEY_BIGINT ( PKEY BIGINT NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_BIGINT ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.PartitionParamBigint;"
                , "Type mismatch between partition column and partition parameter for procedure " +
                "org.voltdb.compiler.procedures.PartitionParamBigint may cause overflow or loss of precision.\n" +
                "Partition column is type VoltType.BIGINT and partition parameter is type VoltType.STRING");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_BIGINT ( PKEY BIGINT NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_BIGINT ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamBigint;" +
                "PARTITION PROCEDURE NotAnnotatedPartitionParamBigint ON TABLE PKEY_BIGINT COLUMN PKEY;"
                , "Type mismatch between partition column and partition parameter for procedure " +
                "org.voltdb.compiler.procedures.NotAnnotatedPartitionParamBigint may cause overflow or loss of precision.\n" +
                "Partition column is type VoltType.BIGINT and partition parameter is type VoltType.STRING");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.PartitionParamInteger;"
                , "Type mismatch between partition column and partition parameter for procedure " +
                "org.voltdb.compiler.procedures.PartitionParamInteger may cause overflow or loss of precision.\n" +
                "Partition column is type VoltType.INTEGER and partition parameter " +
                "is type VoltType.BIGINT");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger;" +
                "PARTITION PROCEDURE NotAnnotatedPartitionParamInteger ON TABLE PKEY_INTEGER COLUMN PKEY;"
                , "Type mismatch between partition column and partition parameter for procedure " +
                "org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger may cause overflow or loss of precision.\n" +
                "Partition column is type VoltType.INTEGER and partition parameter " +
                "is type VoltType.BIGINT");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_SMALLINT ( PKEY SMALLINT NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_SMALLINT ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.PartitionParamSmallint;"
                , "Type mismatch between partition column and partition parameter for procedure " +
                "org.voltdb.compiler.procedures.PartitionParamSmallint may cause overflow or loss of precision.\n" +
                "Partition column is type VoltType.SMALLINT and partition parameter " +
                "is type VoltType.BIGINT");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_SMALLINT ( PKEY SMALLINT NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_SMALLINT ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamSmallint;" +
                "PARTITION PROCEDURE NotAnnotatedPartitionParamSmallint ON TABLE PKEY_SMALLINT COLUMN PKEY;"
                , "Type mismatch between partition column and partition parameter for procedure " +
                "org.voltdb.compiler.procedures.NotAnnotatedPartitionParamSmallint may cause overflow or loss of precision.\n" +
                "Partition column is type VoltType.SMALLINT and partition parameter " +
                "is type VoltType.BIGINT");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_TINYINT ( PKEY TINYINT NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_TINYINT ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.PartitionParamTinyint;"
                , "Type mismatch between partition column and partition parameter for procedure " +
                "org.voltdb.compiler.procedures.PartitionParamTinyint may cause overflow or loss of precision.\n" +
                "Partition column is type VoltType.TINYINT and partition parameter " +
                "is type VoltType.SMALLINT");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_TINYINT ( PKEY TINYINT NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_TINYINT ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamTinyint;" +
                "PARTITION PROCEDURE NotAnnotatedPartitionParamTinyint ON TABLE PKEY_TINYINT COLUMN PKEY;"
                , "Type mismatch between partition column and partition parameter for procedure " +
                "org.voltdb.compiler.procedures.NotAnnotatedPartitionParamTinyint may cause overflow or loss of precision.\n" +
                "Partition column is type VoltType.TINYINT and partition parameter " +
                "is type VoltType.SMALLINT");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_STRING ( PKEY VARCHAR(32) NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_STRING ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.PartitionParamString;"
                , "Type mismatch between partition column and partition parameter for procedure " +
                "org.voltdb.compiler.procedures.PartitionParamString may cause overflow or loss of precision.\n" +
                "Partition column is type VoltType.STRING and partition parameter " +
                "is type VoltType.INTEGER");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_STRING ( PKEY VARCHAR(32) NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_STRING ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamString;" +
                "PARTITION PROCEDURE NotAnnotatedPartitionParamString ON TABLE PKEY_STRING COLUMN PKEY;"
                , "Type mismatch between partition column and partition parameter for procedure " +
                "org.voltdb.compiler.procedures.NotAnnotatedPartitionParamString may cause overflow or loss of precision.\n" +
                "Partition column is type VoltType.STRING and partition parameter " +
                "is type VoltType.INTEGER");

    }

    public static ArrayList<Feedback> compileLiteralSchemaForErrors(String pathToJar, String ddl) {
        final String schemaPath = MiscUtils.writeStringToTempFilePath(ddl);
        final VoltCompiler compiler = new VoltCompiler();
        assertFalse(compileFromDDL(pathToJar, compiler, schemaPath));
        return compiler.m_errors;
    }

    /**
     * @param simpleSchema
     * @return the compiler for optional follow-on checking
     */
    private VoltCompiler compileValidLiteralSchema(final String simpleSchema) {
        final String schemaPath = MiscUtils.writeStringToTempFilePath(simpleSchema);
        VoltCompiler compiler = new VoltCompiler();
        assertTrue(compileFromDDL(compiler, schemaPath));
        return compiler;
    }

    public void testSnapshotSettings() throws IOException {
        String schemaPath = "";
        try {
            final URL url = TPCCProjectBuilder.class.getResource("tpcc-ddl.sql");
            schemaPath = URLDecoder.decode(url.getPath(), "UTF-8");
        } catch (final UnsupportedEncodingException e) {
            e.printStackTrace();
            System.exit(-1);
        }

        CatalogBuilder cb = new CatalogBuilder()
        .addSchema(schemaPath)
        ;
        DeploymentBuilder db = new DeploymentBuilder()
        .setSnapshotSettings("32m", 5, "/tmp", "woobar");
        try {
            File jar = cb.compileToTempJar();
            assertNotNull("Schema compile failed", jar);
            final Catalog cat =
                    VoltCompilerUtils.deserializeCatalogFromCatalogJarfile(jar.getAbsolutePath());
            String deploymentFile = db.writeXMLToTempFile();
            CatalogUtil.compileDeploymentForTest(cat, deploymentFile);
            SnapshotSchedule schedule = CatalogUtil.getDatabase(cat).getSnapshotschedule().get("default");
            assertEquals(32, schedule.getFrequencyvalue());
            assertEquals("m", schedule.getFrequencyunit());
            //Will be empty because the deployment file initialization is what sets this value
            assertEquals("/tmp", schedule.getPath());
            assertEquals("woobar", schedule.getPrefix());
        } finally {
            final File jar = new File("/tmp/snapshot_settings_test.jar");
            jar.delete();
        }
    }

    // TestExportSuite tests most of these options are tested end-to-end; however need to test
    // that a disabled connector is really disabled and that auth data is correct.
    public void testExportSetting() throws IOException {
        CatalogBuilder cb = new CatalogBuilder()
        .addSchema(getClass().getResource("ExportTester-ddl.sql"))
        .setTableAsExportOnly("A")
        .setTableAsExportOnly("B");
        DeploymentBuilder db = new DeploymentBuilder()
        .addExport(false /* disabled */, null, null);
        String pathToDeployment = db.writeXMLToTempFile();
        File jarFile = null;
        try {
            jarFile = cb.compileToTempJar();
            assertNotNull("Catalog failed to compile", jarFile);
            final Catalog cat =
                    VoltCompilerUtils.deserializeCatalogFromCatalogJarfile(jarFile.getAbsolutePath());
            CatalogUtil.compileDeploymentForTest(cat, pathToDeployment);
            Connector connector = CatalogUtil.getDatabase(cat).getConnectors().get("0");
            assertFalse(connector.getEnabled());
        } finally {
            if (jarFile != null) {
                jarFile.delete();
            }
        }
    }

    // test that Export configuration is insensitive to the case of the table name
    public void testExportTableCase() throws IOException {
        CatalogBuilder cb = new CatalogBuilder()
        .addSchema(TestVoltCompiler.class.getResource("ExportTester-ddl.sql"))
        .addStmtProcedure("Dummy", "insert into a values (?, ?, ?);", "a.a_id", 0)
        .addPartitionInfo("A", "A_ID")
        .addPartitionInfo("B", "B_ID")
        .addPartitionInfo("e", "e_id")
        .addPartitionInfo("f", "f_id")
        .setTableAsExportOnly("A") // uppercase DDL, uppercase export
        .setTableAsExportOnly("b") // uppercase DDL, lowercase export
        .setTableAsExportOnly("E") // lowercase DDL, uppercase export
        .setTableAsExportOnly("f"); // lowercase DDL, lowercase export
        DeploymentBuilder db = new DeploymentBuilder()
        .addExport(true /* enabled */, null, null);
        String pathToDeployment = db.writeXMLToTempFile();
        File jarFile = null;
        try {
            jarFile = cb.compileToTempJar();
            assertNotNull("Catalog failed to compile", jarFile);
            final Catalog cat =
                    VoltCompilerUtils.deserializeCatalogFromCatalogJarfile(jarFile.getAbsolutePath());
            CatalogUtil.compileDeploymentForTest(cat, pathToDeployment);
            Connector connector = CatalogUtil.getDatabase(cat).getConnectors().get("0");
            assertTrue(connector.getEnabled());
            // Assert that all tables exist in the connector section of catalog
            assertNotNull(connector.getTableinfo().getIgnoreCase("a"));
            assertNotNull(connector.getTableinfo().getIgnoreCase("b"));
            assertNotNull(connector.getTableinfo().getIgnoreCase("e"));
            assertNotNull(connector.getTableinfo().getIgnoreCase("f"));
        } finally {
            if (jarFile != null) {
                jarFile.delete();
            }
        }
    }

    // test that the source table for a view is not export only
    public void testViewSourceNotExportOnly() throws IOException {
        CatalogBuilder cb = new CatalogBuilder()
        .addSchema(TestVoltCompiler.class.getResource("ExportTesterWithView-ddl.sql"))
        .addStmtProcedure("Dummy", "select * from v_table1r_el_only")
        .setTableAsExportOnly("table1r_el_only")
        ;
        File tempJar = null;
        try {
            tempJar = cb.compileToTempJar();
            assertNull(tempJar);
        }
        finally {
            if (tempJar != null) {
                tempJar.delete();
            }
        }
    }

    // test that a view is not export only
    public void testViewNotExportOnly() throws IOException {
        CatalogBuilder cb = new CatalogBuilder()
        .addSchema(TestVoltCompiler.class.getResource("ExportTesterWithView-ddl.sql"))
        .addStmtProcedure("Dummy", "select * from table1r_el_only")
        .setTableAsExportOnly("v_table1r_el_only");
        File jarFile = null;
        try {
            jarFile = cb.compileToTempJar();
            assertNull("Catalog compile sould have failed for view on export table", jarFile);
        } finally {
            if (jarFile != null) {
                jarFile.delete();
            }
        }
    }

    public void testBadPath() {
        final VoltCompiler compiler = new VoltCompiler();
        final boolean success = compiler.compileWithProjectXML("invalidnonsense", nothing_jar);

        assertFalse(success);
    }

    public void testBadClusterConfig() throws IOException {
        // check no hosts
        ClusterConfig cluster_config = new ClusterConfig(1, 0, 0);
        assertFalse(cluster_config.validate());

        // check no sites-per-hosts
        cluster_config = new ClusterConfig(0, 1, 0);
        assertFalse(cluster_config.validate());
    }

    public void testProcWithBoxedParam() throws IOException {
        final String simpleSchema =
            "create table books (cash integer default 23, title varchar(3) default 'foo', PRIMARY KEY(cash));";

        final String schemaPath = MiscUtils.writeStringToTempFilePath(simpleSchema);

        final String simpleProject =
            "<?xml version=\"1.0\"?>\n" +
            "<project>" +
            "<database name='database'>" +
            "<schemas>" +
            "<schema path='" + schemaPath + "' />" +
            "</schemas>" +
            "<procedures>" +
            //TODO: Define this proc in the ddl and eliminate this project.xml.
            "<procedure class='org.voltdb.compiler.procedures.AddBookBoxed' />" +
            "</procedures>" +
            "</database>" +
            "</project>";

        final String projectPath = MiscUtils.writeStringToTempFilePath(simpleProject);

        final VoltCompiler compiler = new VoltCompiler();
        final boolean success = compiler.compileWithProjectXML(projectPath, testout_jar);
        assertFalse(success);
    }

    public void testDDLWithNoLengthString() throws IOException {

        // DO NOT COPY PASTE THIS INVALID EXAMPLE!
        final String simpleSchema1 =
            "create table books (cash integer default 23, title varchar default 'foo', PRIMARY KEY(cash));";

        final String schemaPath = MiscUtils.writeStringToTempFilePath(simpleSchema1);

        final String simpleProject =
            "<?xml version=\"1.0\"?>\n" +
            "<project>" +
            "<database name='database'>" +
            "<schemas>" +
            "<schema path='" + schemaPath + "' />" +
            "</schemas>" +
            "<procedures>" +
            //TODO: Define these procs in the ddl and eliminate this project.xml.
            "<procedure class='org.voltdb.compiler.procedures.AddBook' />" +
            "<procedure class='Foo'>" +
            "<sql>select * from books;</sql>" +
            "</procedure>" +
            "</procedures>" +
            "</database>" +
            "</project>";

        final String projectPath = MiscUtils.writeStringToTempFilePath(simpleProject);

        final VoltCompiler compiler = new VoltCompiler();

        final boolean success = compiler.compileWithProjectXML(projectPath, testout_jar);
        assertFalse(success);
    }

    public void testDDLWithLongStringInCharacters() throws IOException {
        int length = VoltType.MAX_VALUE_LENGTH_IN_CHARACTERS + 10;
        final String simpleSchema1 =
            "create table books (cash integer default 23, " +
            "title varchar("+length+") default 'foo', PRIMARY KEY(cash));";

        final String schemaPath = MiscUtils.writeStringToTempFilePath(simpleSchema1);

        final String simpleProject =
            "<?xml version=\"1.0\"?>\n" +
            "<project>" +
            "<database name='database'>" +
            "<schemas>" +
            "<schema path='" + schemaPath + "' />" +
            "</schemas>" +
            "</database>" +
            "</project>";

        final String projectPath = MiscUtils.writeStringToTempFilePath(simpleProject);

        final VoltCompiler compiler = new VoltCompiler();

        final boolean success = compiler.compileWithProjectXML(projectPath, testout_jar);
        assertTrue(success);

        // Check warnings
        assertEquals(1, compiler.m_warnings.size());
        String warningMsg = compiler.m_warnings.get(0).getMessage();
        String expectedMsg = "The size of VARCHAR column TITLE in table BOOKS greater than " +
                "262144 will be enforced as byte counts rather than UTF8 character counts. " +
                "To eliminate this warning, specify \"VARCHAR(262154 BYTES)\"";
        assertEquals(expectedMsg, warningMsg);
        Database db = compiler.getCatalogDatabase();
        Column var = db.getTables().get("BOOKS").getColumns().get("TITLE");
        assertTrue(var.getInbytes());
    }

    public void testDDLWithTooLongVarbinaryVarchar() throws IOException {
        int length = VoltType.MAX_VALUE_LENGTH + 10;
        String simpleSchema1 =
                "create table books (cash integer default 23, " +
                        "title varbinary("+length+") , PRIMARY KEY(cash));";

        String error1 = "VARBINARY column size for column BOOKS.TITLE is > "
                + VoltType.MAX_VALUE_LENGTH+" char maximum.";
        checkDDLErrorMessage(simpleSchema1, error1);

        String simpleSchema2 =
                "create table books (cash integer default 23, " +
                        "title varchar("+length+") , PRIMARY KEY(cash));";

        String error2 = "VARCHAR column size for column BOOKS.TITLE is > "
                + VoltType.MAX_VALUE_LENGTH+" char maximum.";
        checkDDLErrorMessage(simpleSchema2, error2);
    }

    public void testNullablePartitionColumn() throws IOException {
        final String simpleSchema =
            "create table books (cash integer default 23, title varchar(3) default 'foo', PRIMARY KEY(cash));" +
            "partition table books on column cash;";

        final String schemaPath = MiscUtils.writeStringToTempFilePath(simpleSchema);

        final String simpleProject =
            "<?xml version=\"1.0\"?>\n" +
            "<project>" +
            "<database name='database'>" +
            "<schemas><schema path='" + schemaPath + "' /></schemas>" +
            //TODO: Define this proc in the ddl and eliminate this project.xml.
            "<procedures><procedure class='org.voltdb.compiler.procedures.AddBook'/></procedures>" +
            "</database>" +
            "</project>";

        final String projectPath = MiscUtils.writeStringToTempFilePath(simpleProject);
        final VoltCompiler compiler = new VoltCompiler();
        final boolean success = compiler.compileWithProjectXML(projectPath, testout_jar);
        assertFalse(success);

        boolean found = false;
        for (final VoltCompiler.Feedback fb : compiler.m_errors) {
            if (fb.message.indexOf("Partition column") > 0)
                found = true;
        }
        assertTrue(found);
    }

    public void testXMLFileWithBadDDL() throws IOException {
        final String simpleSchema =
            "create table books (id integer default 0, strval varchar(33000) default '', PRIMARY KEY(id));";

        final String schemaPath = MiscUtils.writeStringToTempFilePath(simpleSchema);

        final String simpleProject =
            "<?xml version=\"1.0\"?>\n" +
            "<project>" +
            "<database name='database'>" +
            "<schemas><schema path='" + schemaPath + "' /></schemas>" +
            //TODO: Define this proc in the ddl and eliminate this project.xml.
            "<procedures><procedure class='org.voltdb.compiler.procedures.AddBook' /></procedures>" +
            "</database>" +
            "</project>";

        final String projectPath = MiscUtils.writeStringToTempFilePath(simpleProject);

        final VoltCompiler compiler = new VoltCompiler();

        final boolean success = compiler.compileWithProjectXML(projectPath, testout_jar);
        assertFalse(success);
    }

    // NOTE: TPCCTest proc also tests whitespaces regressions in SQL literals
    public void testWithTPCCDDL() {
        String schemaPath = "";
        try {
            final URL url = TPCCProjectBuilder.class.getResource("tpcc-ddl.sql");
            schemaPath = URLDecoder.decode(url.getPath(), "UTF-8");
        } catch (final UnsupportedEncodingException e) {
            e.printStackTrace();
            System.exit(-1);
        }

        final String simpleProject =
            "<?xml version=\"1.0\"?>\n" +
            "<project>" +
            "<database name='database'>" +
            "<schemas><schema path='" + schemaPath + "' /></schemas>" +
            //TODO: Define this proc in the ddl and eliminate this project.xml.
            "<procedures><procedure class='org.voltdb.compiler.procedures.TPCCTestProc' /></procedures>" +
            "</database>" +
            "</project>";

        //System.out.println(simpleProject);

        final String projectPath = MiscUtils.writeStringToTempFilePath(simpleProject);

        final VoltCompiler compiler = new VoltCompiler();
        final boolean success = compiler.compileWithProjectXML(projectPath, testout_jar);
        assertTrue(success);
    }

    public void testSeparateCatalogCompilation() throws Exception {
        final URL url = TPCCProjectBuilder.class.getResource("tpcc-ddl.sql");
        final String schemaPath = URLDecoder.decode(url.getPath(), "UTF-8");

        // Originally, this test mixed in a java procedure via custom project.xml a la:
        //   "<procedures><procedure class='org.voltdb.compiler.procedures.TPCCTestProc' /></procedures>"
        // If that's important to the effectiveness of this test, then the test should probably
        // be extended to A) use a variant of "tpcc-ddl.sql" that adds an extra "CREATE PROCEDURE"
        // and B) minimally validate the proc definition in the catalog so that the test actually
        // fails the next time someone tries to optimize out this important facet.
        // Since this test still passes without the proc, unless emma complains that we've lost coverage
        // for some production code path, assume that the proc was irrelevant to the test case.
        final VoltCompiler compiler1 = new VoltCompiler();
        final VoltCompiler compiler2 = new VoltCompiler();
        Catalog catalog = compiler1.compileCatalogFromDDL(schemaPath);
        final String cat1 = catalog.serialize();
        final boolean success = compiler2.compileFromDDL(testout_jar, schemaPath);
        assertTrue(success);
        final String cat2 = VoltCompilerUtils.readFileFromJarfile(testout_jar, "catalog.txt");
        assertEquals(0, cat1.compareTo(cat2));
    }

    private boolean compileFromDDL(VoltCompiler compiler, String schemaPath)
    {
        return compileFromDDL(testout_jar, compiler, schemaPath);
    }

    private static boolean compileFromLiteralSchema(String jarPath,
            VoltCompiler compiler, String literalSchema)
    {
        final String schemaPath = MiscUtils.writeStringToTempFilePath(literalSchema);
        return compileFromDDL(jarPath, compiler, schemaPath);
    }

    private static boolean compileFromDDL(String pathToJar,
            VoltCompiler compiler, String... schemaPaths) {
        try {
            return compiler.compileFromDDL(pathToJar, schemaPaths);
        }
        catch (VoltCompilerException e) {
            e.printStackTrace();
            fail();
            return false;
        }
    }

    public void testDDLTableTooManyColumns() {
        String schemaPath = "";
        try {
            final URL url = TestVoltCompiler.class.getResource("toowidetable-ddl.sql");
            schemaPath = URLDecoder.decode(url.getPath(), "UTF-8");
        } catch (final UnsupportedEncodingException e) {
            e.printStackTrace();
            System.exit(-1);
        }

        final String simpleProject =
            "<?xml version=\"1.0\"?>\n" +
            "<project>" +
            "<database name='database'>" +
            "<schemas><schema path='" + schemaPath + "' /></schemas>" +
            //TODO: Define this proc in the ddl and eliminate this project.xml.
            "<procedures><procedure class='org.voltdb.compiler.procedures.TPCCTestProc' /></procedures>" +
            "</database>" +
            "</project>";

        //System.out.println(simpleProject);

        final String projectPath = MiscUtils.writeStringToTempFilePath(simpleProject);

        final VoltCompiler compiler = new VoltCompiler();

        final boolean success = compiler.compileWithProjectXML(projectPath, testout_jar);
        assertFalse(success);

        boolean found = false;
        for (final VoltCompiler.Feedback fb : compiler.m_errors) {
            if (fb.message.startsWith("Table MANY_COLUMNS has"))
                found = true;
        }
        assertTrue(found);
    }

    public void testExtraFilesExist() throws IOException {
        String schemaPath = "";
        try {
            final URL url = TPCCProjectBuilder.class.getResource("tpcc-ddl.sql");
            schemaPath = URLDecoder.decode(url.getPath(), "UTF-8");
        } catch (final UnsupportedEncodingException e) {
            e.printStackTrace();
            System.exit(-1);
        }

        final String simpleProject =
            "<?xml version=\"1.0\"?>\n" +
            "<project>" +
            "<database name='database'>" +
            "<schemas><schema path='" + schemaPath + "' /></schemas>" +
            //TODO: Define this proc in the ddl and eliminate this project.xml.
            "<procedures><procedure class='org.voltdb.compiler.procedures.TPCCTestProc' /></procedures>" +
            "</database>" +
            "</project>";

        //System.out.println(simpleProject);

        final String projectPath = MiscUtils.writeStringToTempFilePath(simpleProject);

        final VoltCompiler compiler = new VoltCompiler();

        final boolean success = compiler.compileWithProjectXML(projectPath, testout_jar);
        assertTrue(success);

        final String sql = VoltCompilerUtils.readFileFromJarfile(testout_jar, VoltCompiler.AUTOGEN_DDL_FILE_NAME);
        assertNotNull(sql);
    }

    public void testXMLFileWithELEnabled() throws IOException {
        final String simpleSchema =
            "create table books (cash integer default 23 NOT NULL, title varchar(3) default 'foo');";

        final String schemaPath = MiscUtils.writeStringToTempFilePath(simpleSchema);

        final String simpleProject =
            "<?xml version=\"1.0\"?>\n" +
            "<project>" +
            " <database name='database'>" +
            //TODO: Define these partitionings and proc and export definition
            // in the ddl and eliminate this project.xml.
            "  <partitions><partition table='books' column='cash'/></partitions> " +
            "  <schemas><schema path='" + schemaPath + "' /></schemas>" +
            "  <procedures><procedure class='org.voltdb.compiler.procedures.AddBook' /></procedures>" +
            "  <export>" +
            "    <tables><table name='books'/></tables>" +
            "  </export>" +
            " </database>" +
            "</project>";

        final String projectPath = MiscUtils.writeStringToTempFilePath(simpleProject);
        final VoltCompiler compiler = new VoltCompiler();
        final boolean success = compiler.compileWithProjectXML(projectPath, testout_jar);
        assertTrue(success);

        final Catalog c1 = compiler.getCatalog();
        //System.out.println("PRINTING Catalog 1");
        //System.out.println(c1.serialize());

        final Catalog c2 = VoltCompilerUtils.deserializeCatalogFromCatalogJarfile(testout_jar);
        assertTrue(c2.serialize().equals(c1.serialize()));
    }

    public void testOverrideProcInfo() throws IOException {
        final String simpleSchema =
            "create table books (cash integer default 23 not null, title varchar(3) default 'foo', PRIMARY KEY(cash));" +
            "PARTITION TABLE books ON COLUMN cash;";

        final String schemaPath = MiscUtils.writeStringToTempFilePath(simpleSchema);

        final String simpleProject =
            "<?xml version=\"1.0\"?>\n" +
            "<project>" +
            "<database name='database'>" +
            "<schemas><schema path='" + schemaPath + "' /></schemas>" +
            //TODO: Define this proc in the ddl and eliminate this project.xml.
            "<procedures><procedure class='org.voltdb.compiler.procedures.AddBook' /></procedures>" +
            "</database>" +
            "</project>";

        final String projectPath = MiscUtils.writeStringToTempFilePath(simpleProject);

        final ProcInfoData info = new ProcInfoData();
        info.singlePartition = true;
        info.partitionInfo = "BOOKS.CASH: 0";
        final Map<String, ProcInfoData> overrideMap = new HashMap<String, ProcInfoData>();
        overrideMap.put("AddBook", info);

        final VoltCompiler compiler = new VoltCompiler();
        compiler.setProcInfoOverrides(overrideMap);
        final boolean success = compiler.compileWithProjectXML(projectPath, testout_jar);

        assertTrue(success);

        final Catalog c2 = VoltCompilerUtils.deserializeCatalogFromCatalogJarfile(testout_jar);
        final Database db = CatalogUtil.getDatabase(c2);
        final Procedure addBook = db.getProcedures().get("AddBook");
        assertEquals(true, addBook.getSinglepartition());
    }

    public void testOverrideNonAnnotatedProcInfo() throws IOException {
        final String simpleSchema =
            "create table books (cash integer default 23 not null, title varchar(3) default 'foo', PRIMARY KEY(cash));" +
            "PARTITION TABLE books ON COLUMN cash;" +
            "create procedure from class org.voltdb.compiler.procedures.AddBook;" +
            "partition procedure AddBook ON TABLE books COLUMN cash;";

        final String schemaPath = MiscUtils.writeStringToTempFilePath(simpleSchema);

        final String simpleProject =
                "<?xml version=\"1.0\"?>\n"
                        + "<project>"
                        + "<database name='database'>"
                        + "<schemas>"
                        + "<schema path='" + schemaPath + "' />"
                        + "</schemas>"
                        + "<procedures/>"
                        + "</database>"
                        + "</project>";

        final String projectPath = MiscUtils.writeStringToTempFilePath(simpleProject);

        final ProcInfoData info = new ProcInfoData();
        info.singlePartition = true;
        info.partitionInfo = "BOOKS.CASH: 0";
        final Map<String, ProcInfoData> overrideMap = new HashMap<String, ProcInfoData>();
        overrideMap.put("AddBook", info);

        final VoltCompiler compiler = new VoltCompiler();
        compiler.setProcInfoOverrides(overrideMap);
        final boolean success = compiler.compileWithProjectXML(projectPath, testout_jar);
        assertTrue(success);

        final Catalog c2 = VoltCompilerUtils.deserializeCatalogFromCatalogJarfile(testout_jar);
        final Database db = CatalogUtil.getDatabase(c2);
        final Procedure addBook = db.getProcedures().get("AddBook");
        assertEquals(true, addBook.getSinglepartition());
    }

    public void testBadStmtProcName() throws IOException {
        final String simpleSchema =
            "create table books (cash integer default 23 not null, title varchar(10) default 'foo', PRIMARY KEY(cash));";

        final String schemaPath = MiscUtils.writeStringToTempFilePath(simpleSchema);

        final String simpleProject =
            "<?xml version=\"1.0\"?>\n" +
            "<project>" +
            "<database name='database'>" +
            "<schemas><schema path='" + schemaPath + "' /></schemas>" +
            //TODO: Define these proc and partitioning in the ddl and eliminate this project.xml.
            "<procedures><procedure class='@Foo'><sql>select * from books;</sql></procedure></procedures>" +
            "<partitions><partition table='BOOKS' column='CASH' /></partitions>" +
            "</database>" +
            "</project>";

        final String projectPath = MiscUtils.writeStringToTempFilePath(simpleProject);

        final VoltCompiler compiler = new VoltCompiler();
        final boolean success = compiler.compileWithProjectXML(projectPath, testout_jar);
        assertFalse(success);
    }

    public void testBadDdlStmtProcName() throws IOException {
        final String simpleSchema =
            "create table books (cash integer default 23 not null, title varchar(10) default 'foo', PRIMARY KEY(cash));" +
            "create procedure @Foo as select * from books;";

        final String schemaPath = MiscUtils.writeStringToTempFilePath(simpleSchema);

        final String simpleProject =
            "<?xml version=\"1.0\"?>\n" +
            "<project>" +
            "<database name='database'>" +
            "<schemas><schema path='" + schemaPath + "' /></schemas>" +
            "<procedures/>" +
            //TODO: Define this partitioning in the ddl and eliminate this project.xml.
            "<partitions><partition table='BOOKS' column='CASH' /></partitions>" +
            "</database>" +
            "</project>";

        final String projectPath = MiscUtils.writeStringToTempFilePath(simpleProject);

        final VoltCompiler compiler = new VoltCompiler();
        final boolean success = compiler.compileWithProjectXML(projectPath, testout_jar);
        assertFalse(success);
    }

    public void testGoodStmtProcName() throws IOException {
        final String simpleSchema =
            "create table books (cash integer default 23 not null, title varchar(3) default 'foo', PRIMARY KEY(cash));" +
            "PARTITION TABLE books ON COLUMN cash;";

        final String schemaPath = MiscUtils.writeStringToTempFilePath(simpleSchema);

        final String simpleProject =
            "<?xml version=\"1.0\"?>\n" +
            "<project>" +
            "<database name='database'>" +
            "<schemas><schema path='" + schemaPath + "' /></schemas>" +
            //TODO: Define this proc in the ddl and eliminate this project.xml.
            "<procedures><procedure class='Foo'><sql>select * from books;</sql></procedure></procedures>" +
            "</database>" +
            "</project>";

        final String projectPath = MiscUtils.writeStringToTempFilePath(simpleProject);

        final VoltCompiler compiler = new VoltCompiler();

        final boolean success = compiler.compileWithProjectXML(projectPath, testout_jar);
        assertTrue(success);
    }

    public void testGoodDdlStmtProcName() throws IOException {
        final String simpleSchema =
            "create table books (cash integer default 23 not null, title varchar(3) default 'foo', PRIMARY KEY(cash));" +
            "PARTITION TABLE books ON COLUMN cash;" +
            "CREATE PROCEDURE Foo AS select * from books where cash = ?;" +
            "PARTITION PROCEDURE Foo ON TABLE BOOKS COLUMN CASH PARAMETER 0;";
        compileValidLiteralSchema(simpleSchema);
    }

    public void testUseInnerClassAsProc() throws Exception {
        String simpleSchema =
                "create procedure from class org.voltdb_testprocs.regressionsuites.fixedsql.TestENG2423$InnerProc;";
        compileValidLiteralSchema(simpleSchema);
    }

    public void testMaterializedView() throws IOException {
        final String simpleSchema =
            "create table books (cash integer default 23 NOT NULL, title varchar(10) default 'foo', PRIMARY KEY(cash));\n" +
            "partition table books on column cash;\n" +
            "create view matt (title, cash, num, foo) as select title, cash, count(*), sum(cash) from books group by title, cash;";

        final String schemaPath = MiscUtils.writeStringToTempFilePath(simpleSchema);

        final String simpleProject =
            "<?xml version=\"1.0\"?>\n" +
            "<project>" +
            "<database name='database'>" +
            "<schemas><schema path='" + schemaPath + "' /></schemas>" +
            //TODO: Define this proc in the ddl and eliminate this project.xml.
            "<procedures><procedure class='org.voltdb.compiler.procedures.AddBook' /></procedures>" +
            "</database>" +
            "</project>";

        final String projectPath = MiscUtils.writeStringToTempFilePath(simpleProject);

        final VoltCompiler compiler = new VoltCompiler();
        // final ClusterConfig cluster_config = new ClusterConfig(1, 1, 0, "localhost");

        final boolean success = compiler.compileWithProjectXML(projectPath, testout_jar);
        assertTrue(success);
        final Catalog c1 = compiler.getCatalog();
        final Catalog c2 = VoltCompilerUtils.deserializeCatalogFromCatalogJarfile(testout_jar);
        assertTrue(c2.serialize().equals(c1.serialize()));
    }


    public void testVarbinary() throws IOException {
        final String simpleSchema =
            "create table books (cash integer default 23 NOT NULL, title varbinary(10) default NULL, PRIMARY KEY(cash));" +
            "partition table books on column cash;";

        final String schemaPath = MiscUtils.writeStringToTempFilePath(simpleSchema);

        final String simpleProject =
            "<?xml version=\"1.0\"?>\n" +
            "<project>" +
            "<database name='database'>" +
            "<schemas><schema path='" + schemaPath + "' /></schemas>" +
            //TODO: Define these procs in the ddl and eliminate this project.xml.
            "<procedures>" +
            "<procedure class='get'><sql>select * from books;</sql></procedure>" +
            "<procedure class='i1'><sql>insert into books values(5, 'AA');</sql></procedure>" +
            "<procedure class='i2'><sql>insert into books values(5, ?);</sql></procedure>" +
            "<procedure class='s1'><sql>update books set title = 'bb';</sql></procedure>" +
            "</procedures>" +
            //"<procedures><procedure class='org.voltdb.compiler.procedures.AddBook' /></procedures>" +
            "</database>" +
            "</project>";

        final String projectPath = MiscUtils.writeStringToTempFilePath(simpleProject);

        final VoltCompiler compiler = new VoltCompiler();
        // final ClusterConfig cluster_config = new ClusterConfig(1, 1, 0, "localhost");

        final boolean success = compiler.compileWithProjectXML(projectPath, testout_jar);
        assertTrue(success);
        final Catalog c1 = compiler.getCatalog();
        final Catalog c2 = VoltCompilerUtils.deserializeCatalogFromCatalogJarfile(testout_jar);
        assertTrue(c2.serialize().equals(c1.serialize()));
    }

    public void testDdlProcVarbinary() throws IOException {
        final String simpleSchema =
            "create table books (cash integer default 23 NOT NULL, title varbinary(10) default NULL, PRIMARY KEY(cash));" +
            "partition table books on column cash;" +
            "create procedure get as select * from books;" +
            "create procedure i1 as insert into books values(5, 'AA');" +
            "create procedure i2 as insert into books values(5, ?);" +
            "create procedure s1 as update books set title = 'bb';" +
            "create procedure i3 as insert into books values( ?, ?);" +
            "partition procedure i3 on table books column cash;" +
            "create procedure d1 as delete from books where title = ? and cash = ?;" +
            "partition procedure d1 on table books column cash parameter 1;";

        VoltCompiler compiler = compileValidLiteralSchema(simpleSchema);

        final Catalog c1 = compiler.getCatalog();
        final Catalog c2 = VoltCompilerUtils.deserializeCatalogFromCatalogJarfile(testout_jar);
        assertTrue(c2.serialize().equals(c1.serialize()));
    }

    //
    // There are DDL tests a number of places. TestDDLCompiler seems more about
    // verifying HSQL behaviour. Additionally, there are users of PlannerAideDeCamp
    // that verify plans for various DDL/SQL combinations.
    //
    // I'm going to add some DDL parsing validation tests here, as they seem to have
    // more to do with compiling a catalog.. and there are some related tests already
    // in this file.
    //

    private VoltCompiler compileLiteralSchemaForDDLTest(String literalSchema, boolean expectSuccess) {
        String schemaPath = MiscUtils.writeStringToTempFilePath(literalSchema);
        final String simpleProject =
            "<?xml version=\"1.0\"?>\n" +
            "<project>" +
            "<database name='database'>" +
            "<schemas><schema path='" + schemaPath + "' /></schemas>" +
            //TODO: Define this proc in the ddl and eliminate this project.xml.
            "<procedures><procedure class='sample'><sql>select * from t</sql></procedure></procedures>" +
            "</database>" +
            "</project>";

        final String projectPath = MiscUtils.writeStringToTempFilePath(simpleProject);
        final VoltCompiler compiler = new VoltCompiler();
        final boolean success = compiler.compileWithProjectXML(projectPath, testout_jar);
        assertEquals(expectSuccess, success);
        assertEquals(expectSuccess, ! compiler.hasErrors());
        return compiler;
    }

    /**
     * @param ddl -- valid sql ddl
     * @param tables -- one or more table names supposed to be defined by the ddl
     * @return the compiler to enable follow-on checks.
     */
    private VoltCompiler validateDefinedTables(final String ddl, final String... expectedTableSet) {
        VoltCompiler compiler = compileLiteralSchemaForDDLTest(ddl, true);
        CatalogMap<Table> definedTables = CatalogUtil.getDatabase(compiler.m_catalog).getTables();
        assertEquals(expectedTableSet.length, definedTables.size());
        for (String eachExpected : expectedTableSet) {
            assertNotNull("Expected table: \"" + eachExpected + "\"", definedTables.get(eachExpected));
        }
        return compiler;
    }

    public void testDDLCompilerLeadingGarbage() throws IOException {
        final String s =
            "-- a valid comment\n" +
            "- an invalid comment\n" +
            "create table t(id integer);";

        VoltCompiler compiler = compileLiteralSchemaForDDLTest(s, false);
        assertTrue(compiler.hasErrors()); //TODO: improve w/ error message text matching
    }


    public void testDDLCompilerLeadingWhitespace() throws IOException {
        validateDefinedTables(
            "     \n" +
            "\n" +
            "create table t(id integer);"
            , "t");
    }

    public void testDDLCompilerLeadingComment() throws IOException {
        validateDefinedTables(
            "-- this is a leading comment\n" +
            "  -- with some leading whitespace\n" +
            "     create table t(id integer);"
            , "t");
    }

    public void testDDLCompilerLeadingCommentAndHashMarks() throws IOException {
        validateDefinedTables(
            "-- ### this is a leading comment\n" +
            "  -- with some ### leading whitespace\n" +
            "     create table t(id integer);"
            , "t");
    }

    public void testDDLCompilerNoNewlines() throws IOException {
        validateDefinedTables(
            "create table t(id integer); create table r(id integer);"
            , "t", "r");
    }

    public void testDDLCompilerSplitLines() throws IOException {
        validateDefinedTables(
            "create\n" +
            "table\n" +
            "t(id\n" +
            "integer);"
            , "t");
    }

    public void testDDLCompilerTrailingComment1() throws IOException {
        validateDefinedTables(
            "create table t(id integer) -- this is a trailing comment\n" +
            "-- and a line full of comments\n" +
            ";\n"
            , "t");
    }

    public void testDDLCompilerTrailingComment2() throws IOException {
        validateDefinedTables(
            "create table t(id integer) -- this is a trailing comment\n" +
            ";\n"
            , "t");
    }

    public void testDDLCompilerTrailingCommentAndHashMarks() throws IOException {
        validateDefinedTables(
            "create table t(id varchar(128) default '###')  -- ### this ###### is a trailing comment\n" +
            ";\n"
            , "t");
    }

    public void testDDLCompilerTrailingComment3() throws IOException {
        validateDefinedTables(
            "create table t(id integer) -- this is a trailing comment\n" +
            "-- and a line full of comments\n" +
            ";"
            , "t");
    }

    public void testDDLCompilerTrailingComment4() throws IOException {
        validateDefinedTables(
            "create table t(id integer) -- this is a trailing comment\n" +
            ";"
            , "t");
    }

    public void testDDLCompilerTrailingComment5() throws IOException {
        validateDefinedTables(
            "create table t(id integer) -- this is a trailing comment\n" +
            "-- and a line full of comments\n" +
            "    ;\n"
            , "t");
    }

    public void testDDLCompilerTrailingComment6() throws IOException {
        validateDefinedTables(
            "create table t(id integer) -- this is a trailing comment\n" +
            "-- and a line full of comments\n" +
            "    ;\n" +
            "-- ends with a comment\n"
            , "t");
    }

    public void testDDLCompilerInvalidStatement() throws IOException {
        final String s =
            "create table t for justice -- with a comment\n";
        VoltCompiler compiler = compileLiteralSchemaForDDLTest(s, false);
        assertTrue(compiler.hasErrors()); //TODO: improve w/ error message text matching
    }

    public void testDDLCompilerCommentThatLooksLikeStatement() throws IOException {
        validateDefinedTables(
            "create table t(id integer); -- create table r(id integer);"
            , "t");
    }

    public void testDDLCompilerLeadingSemicolon() throws IOException {
        final String s = "; create table t(id integer);";
        VoltCompiler compiler = compileLiteralSchemaForDDLTest(s, false);
        assertTrue(compiler.hasErrors()); //TODO: improve w/ error message text matching
    }

    public void testDDLCompilerMultipleStatementsOnMultipleLines() throws IOException {
        validateDefinedTables(
            "create table t(id integer); create\n" +
            "table r(id integer); -- second table"
            , "t", "r");
    }

    public void testDDLCompilerStringLiteral() throws IOException {
        VoltCompiler compiler = validateDefinedTables(
            "create table t(id varchar(3) default 'abc');"
            , "t");
        Table tbl = CatalogUtil.getDatabase(compiler.m_catalog).getTables().getIgnoreCase("t");
        String defaultvalue = tbl.getColumns().getIgnoreCase("id").getDefaultvalue();
        assertTrue(defaultvalue.equals("abc"));
    }

    public void testDDLCompilerSemiColonInStringLiteral() throws IOException {
        VoltCompiler compiler = validateDefinedTables(
            "create table t(id varchar(5) default 'a;bc');"
            , "t");
        Table tbl = CatalogUtil.getDatabase(compiler.m_catalog).getTables().getIgnoreCase("t");
        String defaultvalue = tbl.getColumns().getIgnoreCase("id").getDefaultvalue();
        assertTrue(defaultvalue.equals("a;bc"));
    }

    public void testDDLCompilerDashDashInStringLiteral() throws IOException {
        VoltCompiler compiler = validateDefinedTables(
            "create table t(id varchar(5) default 'a--bc');"
            , "t");
        Table tbl = CatalogUtil.getDatabase(compiler.m_catalog).getTables().getIgnoreCase("t");
        String defaultvalue = tbl.getColumns().getIgnoreCase("id").getDefaultvalue();
        assertTrue(defaultvalue.equals("a--bc"));
    }

    public void testDDLCompilerNewlineInStringLiteral() throws IOException {
        VoltCompiler compiler = validateDefinedTables(
            "create table t(id varchar(5) default 'a\n" + "bc');"
            , "t");
        Table tbl = CatalogUtil.getDatabase(compiler.m_catalog).getTables().getIgnoreCase("t");
        String defaultvalue = tbl.getColumns().getIgnoreCase("id").getDefaultvalue();

        // In the debugger, this looks valid at parse time but is mangled somewhere
        // later, perhaps in HSQL or in the catalog assembly?
        // ENG-681
        System.out.println(defaultvalue);
        // assertTrue(defaultvalue.equals("a\nbc"));
    }

    public void testDDLCompilerEscapedStringLiterals() throws IOException {
        VoltCompiler compiler = validateDefinedTables(
            "create table t(id varchar(10) default 'a''b''''c');"
            , "t");
        Table tbl = CatalogUtil.getDatabase(compiler.m_catalog).getTables().getIgnoreCase("t");
        String defaultvalue = tbl.getColumns().getIgnoreCase("id").getDefaultvalue();
        assertTrue(defaultvalue.equals("a'b''c"));
    }

    // Test that DDLCompiler's index creation adheres to the rules implicit in
    // the EE's tableindexfactory.  Currently (10/3/2010) these are:
    // All column types can be used in a tree array.  Only int types can
    // be used in hash tables or array indexes

    String[] column_types = {"tinyint", "smallint", "integer", "bigint",
                            "float", "varchar(10)", "timestamp", "decimal"};

    IndexType[] default_index_types = {IndexType.BALANCED_TREE,
                                    IndexType.BALANCED_TREE,
                                    IndexType.BALANCED_TREE,
                                    IndexType.BALANCED_TREE,
                                    IndexType.BALANCED_TREE,
                                    IndexType.BALANCED_TREE,
                                    IndexType.BALANCED_TREE,
                                    IndexType.BALANCED_TREE};

    boolean[] can_be_hash = {true, true, true, true, false, false, true, false};
    boolean[] can_be_tree = {true, true, true, true, true, true, true, true};

    public void testDDLCompilerIndexDefaultTypes()
    {
        for (int i = 0; i < column_types.length; i++) {
            String s =
                "create table t(id " + column_types[i] + " not null, num integer not null);\n" +
                "create index idx_t_id on t(id);\n" +
                "create index idx_t_idnum on t(id,num);";
            VoltCompiler compiler = compileLiteralSchemaForDDLTest(s, true);
            Table t = CatalogUtil.getDatabase(compiler.m_catalog).getTables().getIgnoreCase("t");
            assertEquals(default_index_types[i].getValue(),
                        t.getIndexes().getIgnoreCase("idx_t_id").getType());
            assertEquals(default_index_types[i].getValue(),
                        t.getIndexes().getIgnoreCase("idx_t_idnum").getType());
        }
    }

    public void testDDLCompilerHashIndexAllowed()
    {
        for (int i = 0; i < column_types.length; i++) {
            final String s =
                "create table t(id " + column_types[i] + " not null, num integer not null);\n" +
                "create index idx_t_id_hash on t(id);\n" +
                "create index idx_t_idnum_hash on t(id,num);";
            VoltCompiler compiler = compileLiteralSchemaForDDLTest(s, can_be_hash[i]);
            if (can_be_hash[i]) {
                // do appropriate index exists checks
                Table t = CatalogUtil.getDatabase(compiler.m_catalog).getTables().getIgnoreCase("t");
                assertEquals(IndexType.HASH_TABLE.getValue(),
                            t.getIndexes().getIgnoreCase("idx_t_id_hash").getType());
                assertEquals(IndexType.HASH_TABLE.getValue(),
                            t.getIndexes().getIgnoreCase("idx_t_idnum_hash").getType());
            }
            else {
                assertTrue(compiler.hasErrors()); //TODO: improve w/ error message text matching
            }
        }
    }

    public void testUniqueIndexAllowed()
    {
        final String s =
                "create table t(id integer not null, num integer not null);\n" +
                "create unique index idx_t_unique on t(id,num);\n" +
                "create index idx_t on t(num);";
        VoltCompiler compiler = compileLiteralSchemaForDDLTest(s, true);
        Table t = CatalogUtil.getDatabase(compiler.m_catalog).getTables().getIgnoreCase("t");
        assertTrue(t.getIndexes().getIgnoreCase("idx_t_unique").getUnique());
        assertFalse(t.getIndexes().getIgnoreCase("idx_t").getUnique());
        // also validate that simple column indexes don't trigger the generalized expression index handling
        String noExpressionFound = "";
        assertEquals(noExpressionFound, t.getIndexes().getIgnoreCase("idx_t_unique").getExpressionsjson());
        assertEquals(noExpressionFound, t.getIndexes().getIgnoreCase("idx_t").getExpressionsjson());
    }

    public void testFunctionIndexAllowed()
    {
        final String s =
                "create table t(id integer not null, num integer not null);\n" +
                "create unique index idx_ft_unique on t(abs(id+num));\n" +
                "create index idx_ft on t(abs(num));";
        VoltCompiler compiler = compileLiteralSchemaForDDLTest(s, true);
        Table t = CatalogUtil.getDatabase(compiler.m_catalog).getTables().getIgnoreCase("t");
        assertTrue(t.getIndexes().getIgnoreCase("idx_ft_unique").getUnique());
        assertFalse(t.getIndexes().getIgnoreCase("idx_ft").getUnique());
        // Validate that general expression indexes get properly annotated with an expressionjson attribute
        String noExpressionFound = "";
        assertNotSame(noExpressionFound, t.getIndexes().getIgnoreCase("idx_ft_unique").getExpressionsjson());
        assertNotSame(noExpressionFound, t.getIndexes().getIgnoreCase("idx_ft").getExpressionsjson());
    }

    public void testDDLCompilerVarcharTreeIndexAllowed()
    {
        for (int i = 0; i < column_types.length; i++)
        {
            final String s =
                "create table t(id " + column_types[i] + " not null, num integer not null);\n" +
                "create index idx_t_id_tree on t(id);\n" +
                "create index idx_t_idnum_tree on t(id,num);";
            VoltCompiler compiler = compileLiteralSchemaForDDLTest(s, can_be_tree[i]);
            Table t = CatalogUtil.getDatabase(compiler.m_catalog).getTables().getIgnoreCase("t");
            assertEquals(IndexType.BALANCED_TREE.getValue(),
                        t.getIndexes().getIgnoreCase("idx_t_id_tree").getType());
            assertEquals(IndexType.BALANCED_TREE.getValue(),
                        t.getIndexes().getIgnoreCase("idx_t_idnum_tree").getType());
        }
    }

    public void testDDLCompilerTwoIdenticalIndexes()
    {
        VoltCompiler compiler = validateDefinedTables(
                "create table t(id integer not null, num integer not null);\n" +
                "create index idx_t_idnum1 on t(id,num);\n" +
                "create index idx_t_idnum2 on t(id,num);"
                , "t");
        assertTrue(compiler.hasErrorsOrWarnings());
    }

    public void testDDLCompilerSameNameIndexesOnTwoTables()
    {
        final String s =
                "create table t1(id integer not null, num integer not null);\n" +
                "create table t2(id integer not null, num integer not null);\n" +
                "create index idx_t_idnum on t1(id,num);\n" +
                "create index idx_t_idnum on t2(id,num);";

        failToCompileLiteralDdl(s);
    }

    public void testDDLCompilerTwoCoveringIndexes()
    {
        validateDefinedTables(
                "create table t(id integer not null, num integer not null);\n" +
                "create index idx_t_idnum_hash on t(id,num);\n" +
                "create index idx_t_idnum_tree on t(id,num);"
                , "t");
    }

    public void testDDLCompilerTwoSwappedOrderIndexes()
    {
        VoltCompiler compiler = validateDefinedTables(
                "create table t(id integer not null, num integer not null);\n" +
                "create index idx_t_idnum_a on t(num,id);\n" +
                "create index idx_t_idnum_b on t(id,num);"
                , "t");
        assertFalse(compiler.hasErrorsOrWarnings());
    }

    public void testDDLCompilerDropOneOfThreeIndexes()
    {
        VoltCompiler compiler = validateDefinedTables(
                "create table t(id integer not null, num integer not null);\n" +
                "create index idx_t_idnum_a on t(num,id);\n" +
                "create index idx_t_idnum_b on t(id,num);\n" +
                "create index idx_t_idnum_c on t(id,num);\n"
                , "t");
        assertTrue(compiler.hasErrorsOrWarnings());
        int foundCount = 0;
        for (VoltCompiler.Feedback f : compiler.m_warnings) {
            if (f.message.contains("Dropping index")) {
                foundCount++;
            }
        }
        assertEquals(1, foundCount);
    }

    public void testDDLCompilerUniqueAndNonUniqueIndexOnSameColumns()
    {
        validateDefinedTables(
                "create table t(id integer not null, num integer not null);\n" +
                "create unique index idx_t_idnum_unique on t(id,num);\n" +
                "create index idx_t_idnum on t(id,num);"
                , "t");
    }

    public void testDDLCompilerTwoIndexesWithSameName()
    {
        final String s =
                "create table t(id integer not null, num integer not null);\n" +
                "create index idx_t_idnum on t(id);\n" +
                "create index idx_t_idnum on t(id,num);";

        failToCompileLiteralDdl(s);
    }

    /**
     * @param ddl
     */
    private void failToCompileLiteralDdl(final String ddl) {
        compileLiteralSchemaForDDLTest(ddl, false);
    }

    public void testDDLCompilerIndexesOrMatViewContainSQLFunctionNOW()
    {
        // Test indexes.
        String ddl = "";
        String errorIndexMsg = "Index IDX_T_TM cannot include the function NOW or CURRENT_TIMESTAMP.";
        ddl = "create table t(id integer not null, tm timestamp);\n" +
              "create index idx_t_tm on t(since_epoch(second, CURRENT_TIMESTAMP) - since_epoch(second, tm));";
        checkDDLErrorMessage(ddl, errorIndexMsg);

        ddl = "create table t(id integer not null, tm timestamp);\n" +
              "create index idx_t_tm on t(since_epoch(second, NOW) - since_epoch(second, tm));";
        checkDDLErrorMessage(ddl, errorIndexMsg);

        ddl = "create table t(id integer not null, tm timestamp);\n" +
              "create index idx_t_tm on t(CURRENT_TIMESTAMP);";
        checkDDLErrorMessage(ddl, errorIndexMsg);

        // Test MatView.
        String errorMatviewMsg = "Materialized view \"MY_VIEW\" cannot include the function NOW or CURRENT_TIMESTAMP.";
        ddl = "create table t(id integer not null, tm timestamp);\n" +
              "create view my_view as select since_epoch(second, CURRENT_TIMESTAMP) - since_epoch(second, tm), " +
              "count(*) from t group by since_epoch(second, CURRENT_TIMESTAMP) - since_epoch(second, tm);";
        checkDDLErrorMessage(ddl, errorMatviewMsg);

        ddl = "create table t(id integer not null, tm timestamp);\n" +
                "create view my_view as select since_epoch(second, NOW) - since_epoch(second, tm), " +
                "count(*) from t group by since_epoch(second, NOW) - since_epoch(second, tm);";
        checkDDLErrorMessage(ddl, errorMatviewMsg);

        ddl = "create table t(id integer not null, tm timestamp);\n" +
                "create view my_view as select tm, count(*), count(CURRENT_TIMESTAMP)  from t group by tm;";
        checkDDLErrorMessage(ddl, errorMatviewMsg);

        ddl = "create table t(id integer not null, tm timestamp);\n" +
                "create view my_view as select tm, count(*), count(NOW)  from t group by tm;";
        checkDDLErrorMessage(ddl, errorMatviewMsg);

        ddl = "create table t(id integer not null, tm timestamp);\n" +
                "create view my_view as select tm, count(*) from t " +
                "where since_epoch(second, CURRENT_TIMESTAMP) - since_epoch(second, tm) > 60 " +
                "group by tm;";
        checkDDLErrorMessage(ddl, errorMatviewMsg);
    }

    private static final String msgP = "does not include the partitioning column";
    private static final String msgPR =
            "ASSUMEUNIQUE is not valid for an index that includes the partitioning column. " +
            "Please use UNIQUE instead";

    public void testColumnUniqueGiveException()
    {
        String schema;

        // (1) ****** Replicate tables
        // A unique index on the non-primary key for replicated table gets no error.
        schema = "create table t0 (id bigint not null, name varchar(32) not null UNIQUE, age integer,  primary key (id));\n";
        checkValidUniqueAndAssumeUnique(schema, null, null);

        // Similar to above, but use a different way to define unique column.
        schema = "create table t0 (id bigint not null, name varchar(32) not null, age integer,  " +
                "primary key (id), UNIQUE (name) );\n";
        checkValidUniqueAndAssumeUnique(schema, null, null);


        // (2) ****** Partition Table: UNIQUE valid, ASSUMEUNIQUE not valid
        // A unique index on the partitioning key ( no primary key) gets no error.
        schema = "create table t0 (id bigint not null UNIQUE, name varchar(32) not null, age integer);\n" +
                "PARTITION TABLE t0 ON COLUMN id;\n";
        checkValidUniqueAndAssumeUnique(schema, null, msgPR);

        // Similar to above, but use a different way to define unique column.
        schema = "create table t0 (id bigint not null, name varchar(32) not null, age integer,  " +
                "primary key (id), UNIQUE(id) );\n" +
                "PARTITION TABLE t0 ON COLUMN id;\n";
        checkValidUniqueAndAssumeUnique(schema, null, msgPR);

        // A unique index on the partitioning key ( also primary key) gets no error.
        schema = "create table t0 (id bigint not null UNIQUE, name varchar(32) not null, age integer,  primary key (id));\n" +
                "PARTITION TABLE t0 ON COLUMN id;\n";
        checkValidUniqueAndAssumeUnique(schema, null, msgPR);


        // A unique compound index on the partitioning key and another column gets no error.
        schema = "create table t0 (id bigint not null, name varchar(32) not null, age integer,  " +
                "UNIQUE (id, age), primary key (id));\n" +
                "PARTITION TABLE t0 ON COLUMN id;\n";
        checkValidUniqueAndAssumeUnique(schema, null, msgPR);

        // A unique index on the partitioning key and an expression like abs(age) gets no error.
        schema = "create table t0 (id bigint not null, name varchar(32) not null, age integer,  " +
                "primary key (id), UNIQUE (id, abs(age)) );\n" +
                "PARTITION TABLE t0 ON COLUMN id;\n";
        checkValidUniqueAndAssumeUnique(schema, null, msgPR);


        // (3) ****** Partition Table: UNIQUE not valid
        // A unique index on the partitioning key ( non-primary key) gets one error.
        schema = "create table t0 (id bigint not null, name varchar(32) not null UNIQUE, age integer,  primary key (id));\n" +
                "PARTITION TABLE t0 ON COLUMN name;\n";
        checkValidUniqueAndAssumeUnique(schema, msgP, msgPR);

        // A unique index on the partitioning key ( no primary key) gets one error.
        schema = "create table t0 (id bigint not null, name varchar(32) not null UNIQUE, age integer);\n" +
                "PARTITION TABLE t0 ON COLUMN id;\n";
        checkValidUniqueAndAssumeUnique(schema, msgP, null);

        // A unique index on the non-partitioning key gets one error.
        schema = "create table t0 (id bigint not null, name varchar(32) UNIQUE, age integer,  primary key (id));\n" +
                "PARTITION TABLE t0 ON COLUMN id;\n";
        checkValidUniqueAndAssumeUnique(schema, msgP, null);

        // A unique index on an unrelated expression like abs(age) gets a error.
        schema = "create table t0 (id bigint not null, name varchar(32), age integer, UNIQUE (abs(age)), primary key (id));\n" +
                "PARTITION TABLE t0 ON COLUMN id;\n";
        checkValidUniqueAndAssumeUnique(schema, msgP, null);


        // A unique index on an expression of the partitioning key like substr(1, 2, name) gets two errors.
        schema = "create table t0 (id bigint not null, name varchar(32) not null, age integer,  " +
                "primary key (id), UNIQUE (substr(name, 1, 2 )) );\n" +
                "PARTITION TABLE t0 ON COLUMN name;\n";
        // 1) unique index, 2) primary key
        checkValidUniqueAndAssumeUnique(schema, msgP, msgP);

        // A unique index on the non-partitioning key, non-partitioned column gets two errors.
        schema = "create table t0 (id bigint not null, name varchar(32) not null, age integer UNIQUE,  primary key (id));\n" +
                "PARTITION TABLE t0 ON COLUMN name;\n";
        // 1) unique index, 2) primary key
        checkValidUniqueAndAssumeUnique(schema, msgP, msgP);

        // unique/assumeunique constraint added via ALTER TABLE to replicated table
        schema = "create table t0 (id bigint not null, name varchar(32) not null);\n" +
                "ALTER TABLE t0 ADD UNIQUE(name);";
        checkValidUniqueAndAssumeUnique(schema, null, null);

        // unique/assumeunique constraint added via ALTER TABLE to partitioned table
        schema = "create table t0 (id bigint not null, name varchar(32) not null);\n" +
                "PARTITION TABLE t0 ON COLUMN id;\n" +
                "ALTER TABLE t0 ADD UNIQUE(name);";
        checkValidUniqueAndAssumeUnique(schema, msgP, null);

        // ENG-7242, kinda
        // (tests the assumeuniqueness constraint is preserved, obliquely, see
        // TestAdhocAlterTable for more thorough tests)
        schema = "create table t0 (id bigint not null, name varchar(32) not null, val integer);\n" +
                "PARTITION TABLE t0 ON COLUMN id;\n" +
                "ALTER TABLE t0 ADD UNIQUE(name);\n" +
                "ALTER TABLE t0 DROP COLUMN val;\n";
        checkValidUniqueAndAssumeUnique(schema, msgP, null);

        // ENG-7304, that we can pass functions to constrant definitions in alter table
        schema = "create table t0 (id bigint not null, val2 integer not null, val integer);\n" +
                "PARTITION TABLE t0 ON COLUMN id;\n" +
                "ALTER TABLE t0 ADD UNIQUE(abs(val2));\n" +
                "ALTER TABLE t0 DROP COLUMN val;\n";
        checkValidUniqueAndAssumeUnique(schema, msgP, null);
    }

    public void testUniqueIndexGiveException() {
        String schema;

        // (1) ****** Replicate tables
        // A unique index on the non-primary key for replicated table gets no error.
        schema = "create table t0 (id bigint not null, name varchar(32) not null, age integer,  primary key (id));\n" +
                "CREATE UNIQUE INDEX user_index0 ON t0 (name) ;";
        checkValidUniqueAndAssumeUnique(schema, null, null);


        // (2) ****** Partition Table: UNIQUE valid, ASSUMEUNIQUE not valid
        // A unique index on the partitioning key ( no primary key) gets no error.
        schema = "create table t0 (id bigint not null, name varchar(32) not null, age integer);\n" +
                "PARTITION TABLE t0 ON COLUMN id;\n" +
                "CREATE UNIQUE INDEX user_index1 ON t0 (id) ;";
        checkValidUniqueAndAssumeUnique(schema, null, msgPR);

        // A unique index on the partitioning key ( also primary key) gets no error.
        schema = "create table t0 (id bigint not null, name varchar(32) not null, age integer,  primary key (id));\n" +
                "PARTITION TABLE t0 ON COLUMN id;\n" +
                "CREATE UNIQUE INDEX user_index2 ON t0 (id) ;";
        checkValidUniqueAndAssumeUnique(schema, null, msgPR);

        // A unique compound index on the partitioning key and another column gets no error.
        schema = "create table t0 (id bigint not null, name varchar(32) not null, age integer,  primary key (id));\n" +
                "PARTITION TABLE t0 ON COLUMN id;\n" +
                "CREATE UNIQUE INDEX user_index3 ON t0 (id, age) ;";
        checkValidUniqueAndAssumeUnique(schema, null, msgPR);

        // A unique index on the partitioning key and an expression like abs(age) gets no error.
        schema = "create table t0 (id bigint not null, name varchar(32) not null, age integer,  primary key (id));\n" +
                "PARTITION TABLE t0 ON COLUMN id;\n" +
                "CREATE UNIQUE INDEX user_index4 ON t0 (id, abs(age)) ;";
        checkValidUniqueAndAssumeUnique(schema, null, msgPR);


        // (3) ****** Partition Table: UNIQUE not valid
        // A unique index on the partitioning key ( no primary key) gets one error.
        schema = "create table t0 (id bigint not null, name varchar(32) not null, age integer);\n" +
                "PARTITION TABLE t0 ON COLUMN id;\n" +
                "CREATE UNIQUE INDEX user_index7 ON t0 (name) ;";
        checkValidUniqueAndAssumeUnique(schema, msgP, null);

        // A unique index on the non-partitioning key gets one error.
        schema = "create table t0 (id bigint not null, name varchar(32), age integer,  primary key (id));\n" +
                "PARTITION TABLE t0 ON COLUMN id;\n" +
                "CREATE UNIQUE INDEX user_index8 ON t0 (name) ;";
        checkValidUniqueAndAssumeUnique(schema, msgP, null);

        // A unique index on an unrelated expression like abs(age) gets a error.
        schema = "create table t0 (id bigint not null, name varchar(32), age integer,  primary key (id));\n" +
                "PARTITION TABLE t0 ON COLUMN id;\n" +
                "CREATE UNIQUE INDEX user_index9 ON t0 (abs(age)) ;";
        checkValidUniqueAndAssumeUnique(schema, msgP, null);

        // A unique index on the partitioning key ( non-primary key) gets one error.
        schema = "create table t0 (id bigint not null, name varchar(32) not null, age integer,  primary key (id));\n" +
                "PARTITION TABLE t0 ON COLUMN name;";
        checkValidUniqueAndAssumeUnique(schema, msgP, msgP);

        // A unique index on an expression of the partitioning key like substr(1, 2, name) gets two errors.
        schema = "create table t0 (id bigint not null, name varchar(32) not null, age integer,  primary key (id));\n" +
                "PARTITION TABLE t0 ON COLUMN name;\n" +
                "CREATE UNIQUE INDEX user_index10 ON t0 (substr(name, 1, 2 )) ;";
        // 1) unique index, 2) primary key
        checkValidUniqueAndAssumeUnique(schema, msgP, msgP);

        // A unique index on the non-partitioning key, non-partitioned column gets two errors.
        schema = "create table t0 (id bigint not null, name varchar(32) not null, age integer,  primary key (id));\n" +
                "PARTITION TABLE t0 ON COLUMN name;\n" +
                "CREATE UNIQUE INDEX user_index12 ON t0 (age) ;";
        // 1) unique index, 2) primary key
        checkValidUniqueAndAssumeUnique(schema, msgP, msgP);
    }


    public void testDDLCompilerMatView()
    {
        // Test MatView.
        String ddl;

        ddl = "create table t(id integer not null, num integer, wage integer);\n" +
                "create view my_view1 (num, total) " +
                "as select num, count(*) from (select num from t) subt group by num; \n";
        checkDDLErrorMessage(ddl, "Materialized view \"MY_VIEW1\" with subquery sources is not supported.");

        ddl = "create table t1(id integer not null, num integer, wage integer);\n" +
                "create table t2(id integer not null, num integer, wage integer);\n" +
                "create view my_view1 (id, num, total) " +
                "as select t1.id, t2.num, count(*) from t1 join t2 on t1.id = t2.id group by t1.id, t2.num; \n";
        checkDDLErrorMessage(ddl, "Materialized view \"MY_VIEW1\" has 2 sources. Only one source table is allowed.");

        ddl = "create table t1(id integer not null, num integer, wage integer);\n" +
                "create table t2(id integer not null, num integer, wage integer);\n" +
                "create view my_view1 (id, num, total) " +
                "as select t1.id, st2.num, count(*) from t1 join (select id ,num from t2) st2 on t1.id = st2.id group by t1.id, st2.num; \n";
        checkDDLErrorMessage(ddl, "Materialized view \"MY_VIEW1\" with subquery sources is not supported.");

        ddl = "create table t(id integer not null, num integer);\n" +
                "create view my_view as select num, count(*) from t group by num order by num;";
        checkDDLErrorMessage(ddl, "Materialized view \"MY_VIEW\" with ORDER BY clause is not supported.");

        ddl = "create table t(id integer not null, num integer, wage integer);\n" +
                "create view my_view1 (num, total, sumwage) " +
                "as select num, count(*), sum(wage) from t group by num; \n" +

                "create view my_view2 (num, total, sumwage) " +
                "as select num, count(*), sum(sumwage) from my_view1 group by num; ";
        checkDDLErrorMessage(ddl, "A materialized view (MY_VIEW2) can not be defined on another view (MY_VIEW1)");

        ddl = "create table t(id integer not null, num integer);\n" +
                "create view my_view as select num, count(*) from t group by num limit 1;";
        checkDDLErrorMessage(ddl, "Materialized view \"MY_VIEW\" with LIMIT or OFFSET clause is not supported.");

        ddl = "create table t(id integer not null, num integer);\n" +
                "create view my_view as select num, count(*) from t group by num limit 1 offset 10;";
        checkDDLErrorMessage(ddl, "Materialized view \"MY_VIEW\" with LIMIT or OFFSET clause is not supported.");

        ddl = "create table t(id integer not null, num integer);\n" +
                "create view my_view as select num, count(*) from t group by num having count(*) > 3;";
        checkDDLErrorMessage(ddl, "Materialized view \"MY_VIEW\" with HAVING clause is not supported.");
    }

    public void testDDLCompilerTableLimit()
    {
        String ddl;

        // Test CREATE
        // test failed cases
        ddl = "create table t(id integer not null, num integer," +
                "CONSTRAINT tblimit1 LIMIT PARTITION ROWS 6xx);";
        checkDDLErrorMessage(ddl, "unexpected token: XX");

        ddl = "create table t(id integer not null, num integer," +
                "CONSTRAINT tblimit1 LIMIT PARTITION ROWS 66666666666666666666666666666666);";
        checkDDLErrorMessage(ddl, "incompatible data type in operation");

        ddl = "create table t(id integer not null, num integer," +
                "CONSTRAINT tblimit1 LIMIT PARTITION ROWS -10);";
        checkDDLErrorMessage(ddl, "Invalid constraint limit number '-10'");

        ddl = "create table t(id integer not null, num integer," +
                "CONSTRAINT tblimit1 LIMIT PARTITION ROWS 5, CONSTRAINT tblimit2 LIMIT PARTITION ROWS 7);";
        checkDDLErrorMessage(ddl, "Multiple LIMIT PARTITION ROWS constraints on table T are forbidden");

        ddl = "create table t(id integer not null, num integer," +
                "CONSTRAINT tblimit1 LIMIT PARTITION Row 6);";
        checkDDLErrorMessage(ddl, "unexpected token: ROW required: ROWS");

        ddl = "create table t(id integer not null, num integer," +
                "CONSTRAINT tblimit1 LIMIT Rows 6);";
        checkDDLErrorMessage(ddl, "unexpected token: ROWS required: PARTITION");


        // Test success cases
        ddl = "create table t(id integer not null, num integer," +
                "CONSTRAINT tblimit1 LIMIT PARTITION ROWS 6);";
        checkDDLErrorMessage(ddl, null);

        ddl = "create table t(id integer not null, num integer," +
                "LIMIT PARTITION ROWS 6);";
        checkDDLErrorMessage(ddl, null);

        // Test alter
        // Test failed cases
        ddl = "create table t(id integer not null, num integer);" +
              "alter table t add constraint foo LIMIT PARTITION ROWS 6XX;";
        checkDDLErrorMessage(ddl, "unexpected token: XX");

        ddl = "create table t(id integer not null, num integer);" +
              "alter table t add constraint foo LIMIT PARTITION ROWS 66666666666666666666666;";
        checkDDLErrorMessage(ddl, "incompatible data type in operation");

        ddl = "create table t(id integer not null, num integer);" +
              "alter table t add constraint foo LIMIT PARTITION ROWS -10;";
        checkDDLErrorMessage(ddl, "Invalid constraint limit number '-10'");

        ddl = "create table t(id integer not null, num integer);" +
              "alter table t add constraint foo LIMIT PARTITION ROW 6;";
        checkDDLErrorMessage(ddl, "unexpected token: ROW required: ROWS");

        ddl = "create table t(id integer not null, num integer);" +
              "alter table t add constraint foo LIMIT ROWS 6;";
        checkDDLErrorMessage(ddl, "unexpected token: ROWS required: PARTITION");

        ddl = "create table t(id integer not null, num integer);" +
              "alter table t2 add constraint foo LIMIT PARTITION ROWS 6;";
        checkDDLErrorMessage(ddl, "object not found: T2");

        // Test alter successes
        ddl = "create table t(id integer not null, num integer);" +
              "alter table t add constraint foo LIMIT PARTITION ROWS 6;";
        checkDDLErrorMessage(ddl, null);

        ddl = "create table t(id integer not null, num integer);" +
              "alter table t add LIMIT PARTITION ROWS 6;";
        checkDDLErrorMessage(ddl, null);

        // Successive alter statements are okay
        ddl = "create table t(id integer not null, num integer);" +
              "alter table t add LIMIT PARTITION ROWS 6;" +
              "alter table t add LIMIT PARTITION ROWS 7;";
        checkDDLErrorMessage(ddl, null);

        // Alter after constraint set in create is okay
        ddl = "create table t(id integer not null, num integer," +
                "CONSTRAINT tblimit1 LIMIT PARTITION ROWS 6);" +
              "alter table t add LIMIT PARTITION ROWS 7;";
        checkDDLErrorMessage(ddl, null);

        // Test drop
        // Test failed cases
        ddl = "create table t(id integer not null, num integer);" +
              "alter table t drop constraint tblimit2;";
        checkDDLErrorMessage(ddl, "object not found: TBLIMIT2");

        ddl = "create table t(id integer not null, num integer," +
                "CONSTRAINT tblimit1 LIMIT PARTITION ROWS 6);" +
              "alter table t drop constraint tblimit2;";
        checkDDLErrorMessage(ddl, "object not found: TBLIMIT2");

        ddl = "create table t(id integer not null, num integer);" +
              "alter table t add LIMIT PARTITION ROWS 6;" +
              "alter table t drop constraint tblimit2;";
        checkDDLErrorMessage(ddl, "object not found: TBLIMIT2");

        ddl = "create table t(id integer not null, num integer);" +
              "alter table t drop LIMIT PARTITION ROWS;";
        checkDDLErrorMessage(ddl, "object not found");

        ddl = "create table t(id integer not null, num integer);" +
              "alter table t drop LIMIT PARTITIONS ROWS;";
        checkDDLErrorMessage(ddl, "unexpected token: PARTITIONS required: PARTITION");

        ddl = "create table t(id integer not null, num integer);" +
              "alter table t drop LIMIT PARTITION ROW;";
        checkDDLErrorMessage(ddl, "unexpected token: ROW required: ROWS");

        ddl = "create table t(id integer not null, num integer);" +
              "alter table t drop PARTITION ROWS;";
        checkDDLErrorMessage(ddl, "unexpected token: PARTITION");

        // Test successes
        // named drop
        ddl = "create table t(id integer not null, num integer," +
                "CONSTRAINT tblimit1 LIMIT PARTITION ROWS 6);" +
              "alter table t drop constraint tblimit1;";
        checkDDLErrorMessage(ddl, null);

        // magic drop
        ddl = "create table t(id integer not null, num integer);" +
              "alter table t add LIMIT PARTITION ROWS 6;" +
              "alter table t drop LIMIT PARTITION ROWS;";
        checkDDLErrorMessage(ddl, null);

        // magic drop of named constraint
        ddl = "create table t(id integer not null, num integer," +
                "CONSTRAINT tblimit1 LIMIT PARTITION ROWS 6);" +
              "alter table t drop LIMIT PARTITION ROWS;";
        checkDDLErrorMessage(ddl, null);
    }

    void compileLimitDeleteStmtAndCheckCatalog(String ddl, String expectedMessage, String tblName,
            int expectedLimit, String expectedStmt) {
        VoltCompiler compiler = checkDDLErrorMessage(ddl, expectedMessage);

        if (expectedMessage != null) {
            return;
        }
        // We expected success and got it.  Verify that the catalog looks how we expect
        Table tbl = compiler.getCatalogDatabase().getTables().getIgnoreCase(tblName);
        assertNotNull("Table \"" + tblName + "\" not found in database", tbl);

        if (expectedLimit != -1) {
            assertEquals(expectedLimit, tbl.getTuplelimit());
        }
        else {
            // no limit is represented as a limit of max int.
            assertEquals(Integer.MAX_VALUE, tbl.getTuplelimit());
        }

        Statement stmt = null;
        try {
            stmt = tbl.getTuplelimitdeletestmt().iterator().next();
        }
        catch (NoSuchElementException nse) {
        }

        if (expectedStmt == null) {
            assertTrue("Did not expect to find a LIMIT DELETE statement, but found this one:\n"
                    + (stmt != null ? stmt.getSqltext() : ""),
                    stmt == null);
        }
        else {
            // Make sure we have the delete statement that we expected
            assertTrue("Expected to find LIMIT DELETE statement, found none", stmt != null);

            String sql = stmt.getSqltext();
            if (sql.endsWith(";")) {
                // We seem to add a semicolon somewhere.  I guess that's okay.
                sql = sql.substring(0, sql.length() - 1);
            }

            assertEquals("Did not find the LIMIT DELETE statement that we expected", expectedStmt, sql);
        }
    }

    public void testDDLCompilerAlterTableLimitWithDelete()
    {
        String ddl;

        // See also TestVoltCompilerErrorMsgs for negative tests involving
        // LIMIT PARTITION ROWS <n> EXECUTE (DELETE ...)

        // This exercises adding a limit constraint with a DELETE statement
        ddl = "create table t(id integer not null);\n" +
                "alter table t add limit partition rows 10 execute (delete from t where id > 0);";
        compileLimitDeleteStmtAndCheckCatalog(ddl, null, "t", 10, "delete from t where id > 0");

        // This exercises making a change to the delete statement of an existing constraint
        ddl = "create table t(id integer not null, "
                + "constraint c1 limit partition rows 10 execute (delete from t where id > 0)"
                + ");\n"
                + "alter table t add limit partition rows 15 execute (delete from t where id between 0 and 100);";
        compileLimitDeleteStmtAndCheckCatalog(ddl, null, "t", 15, "delete from t where id between 0 and 100");

        // test dropping a limit contraint with a delete
        ddl = "create table t(id integer not null, "
                + "constraint c1 limit partition rows 10 execute (delete from t where id > 0)"
                + ");\n"
                + "alter table t drop limit partition rows;";
        compileLimitDeleteStmtAndCheckCatalog(ddl, null, "t", -1, null);

        // test dropping constraint by referencing the constraint name
        ddl = "create table t(id integer not null, "
                + "constraint c1 limit partition rows 10 execute (delete from t where id > 0)"
                + ");\n"
                + "alter table t drop constraint c1;";
        compileLimitDeleteStmtAndCheckCatalog(ddl, null, "t", -1, null);

        // test dropping constraint by referencing the constraint name
        // Negative test---got the constraint name wrong
        ddl = "create table t(id integer not null, "
                + "constraint c1 limit partition rows 10 execute (delete from t where id > 0)"
                + ");\n"
                + "alter table t drop constraint c34;";
        compileLimitDeleteStmtAndCheckCatalog(ddl, "object not found", "t", -1, null);

        // Alter the table by removing the LIMIT DELETE statement, but not the row limit
        ddl = "create table t(id integer not null, "
                + "constraint c1 limit partition rows 10 execute (delete from t where id > 0)"
                + ");\n"
                + "alter table t add limit partition rows 10;";
        compileLimitDeleteStmtAndCheckCatalog(ddl, null, "t", 10, null);

        // See also regression testing that ensures EE picks up catalog changes
        // in TestSQLFeaturesNewSuite
    }

    public void testPartitionOnBadType() {
        final String simpleSchema =
            "create table books (cash float default 0.0 NOT NULL, title varchar(10) default 'foo', PRIMARY KEY(cash));";

        final String schemaPath = MiscUtils.writeStringToTempFilePath(simpleSchema);

        final String simpleProject =
            "<?xml version=\"1.0\"?>\n" +
            "<project>" +
            "<database>" +
            "<schemas><schema path='" + schemaPath + "' /></schemas>" +
            //TODO: Define these proc and partitioning in the ddl and eliminate this project.xml.
            "<partitions><partition table='books' column='cash'/></partitions> " +
            "<procedures><procedure class='org.voltdb.compiler.procedures.AddBook' /></procedures>" +
            "</database>" +
            "</project>";

        final String projectPath = MiscUtils.writeStringToTempFilePath(simpleProject);

        final VoltCompiler compiler = new VoltCompiler();
        final boolean success = compiler.compileWithProjectXML(projectPath, testout_jar);
        assertFalse(success);
    }

    public void testOmittedProcedureList() {
        final String simpleSchema =
                "create table books (cash float default 0.0 NOT NULL, title varchar(10) default 'foo', PRIMARY KEY(cash));";

        final String schemaPath = MiscUtils.writeStringToTempFilePath(simpleSchema);

        final String simpleProject =
                "<?xml version=\"1.0\"?>\n" +
                "<project>" +
                "<database>" +
                "<schemas><schema path='" + schemaPath + "' /></schemas>" +
                "</database>" +
                "</project>";

        final String projectPath = MiscUtils.writeStringToTempFilePath(simpleProject);

        final VoltCompiler compiler = new VoltCompiler();
        final boolean success = compiler.compileWithProjectXML(projectPath, testout_jar);
        assertTrue(success);
    }

    public void test3324MPPlan() throws IOException {
        CatalogBuilder cb = new CatalogBuilder(
                "create table blah (pkey integer not null, strval varchar(200), PRIMARY KEY(pkey));\n" +
                "partition table blah on column pkey;\n" +
                "")
        .addStmtProcedure("undeclaredspquery1", "select strval UNDECLARED1 from blah where pkey = ?")
        .addStmtProcedure("undeclaredspquery2", "select strval UNDECLARED2 from blah where pkey = 12")
        .addStmtProcedure("declaredspquery1", "select strval SODECLARED1 from blah where pkey = ?", "blah.pkey", 0)
        .enableDiagnostics()
        ;
        // Currently no way to do this?
        // pb.addStmtProcedure("declaredspquery2", "select strval SODECLARED2 from blah where pkey = 12", "blah.pkey=12");
        assertTrue(cb.compile(Configuration.getPathToCatalogForTest("test3324.jar")));
        List<String> diagnostics = cb.harvestDiagnostics();
        // This asserts that the undeclared SP plans don't mistakenly get SP treatment
        // -- they must each include a RECEIVE plan node.
        assertEquals(2, countStringsMatching(diagnostics, ".*\"UNDECLARED.\".*\"PLAN_NODE_TYPE\":\"RECEIVE\".*"));
        // This asserts that the methods used to prevent undeclared SP plans from getting SP treatment
        // don't over-reach to declared SP plans.
        assertEquals(0, countStringsMatching(diagnostics, ".*\"SODECLARED.\".*\"PLAN_NODE_TYPE\":\"RECEIVE\".*"));
        // System.out.println("test3324MPPlan");
        // System.out.println(diagnostics);
    }

    public void testBadDDLErrorLineNumber() throws IOException {
        final String schema =
            "-- a comment\n" +                          // 1
            "create table books (\n" +                  // 2
            " id integer default 0,\n" +                // 3
            " strval varchar(33000) default '',\n" +    // 4
            " PRIMARY KEY(id)\n" +                      // 5
            ");\n" +                                    // 6
            "\n" +                                      // 7
            "-- another comment\n" +                    // 8
            "create view badview (\n" +                 // 9 * error reported here *
            " id,\n" +
            " COUNT(*),\n" +
            " total\n" +
            " as\n" +
            "select id, COUNT(*), SUM(cnt)\n" +
            " from books\n" +
            " group by id;";
        final String schemaPath = MiscUtils.writeStringToTempFilePath(schema);

        final String project =
            "<?xml version=\"1.0\"?>\n" +
            "<project>" +
            "<database name='database'>" +
            "<schemas><schema path='" + schemaPath + "' /></schemas>" +
            //TODO: Define this proc in the ddl and eliminate this project.xml.
            "<procedures><procedure class='org.voltdb.compiler.procedures.AddBook' /></procedures>" +
            "</database>" +
            "</project>";

        final String projectPath = MiscUtils.writeStringToTempFilePath(project);

        final VoltCompiler compiler = new VoltCompiler();

        final boolean success = compiler.compileWithProjectXML(projectPath, testout_jar);
        assertFalse(success);
        for (Feedback error: compiler.m_errors) {
            assertEquals(9, error.lineNo);
        }
    }


    public void testInvalidCreateProcedureDDL() throws Exception {
        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.NonExistentPartitionParamInteger;" +
                "PARTITION PROCEDURE NonExistentPartitionParamInteger ON TABLE PKEY_INTEGER COLUMN PKEY;"
                , "Cannot load class for procedure: org.voltdb.compiler.procedures.NonExistentPartitionParamInteger");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "PARTITION PROCEDURE NotDefinedPartitionParamInteger ON TABLE PKEY_INTEGER COLUMN PKEY;"
                , "Partition in referencing an undefined procedure \"NotDefinedPartitionParamInteger\"");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.PartitionParamInteger;" +
                "PARTITION PROCEDURE PartitionParamInteger ON TABLE PKEY_WHAAAT COLUMN PKEY;"
                , "PartitionParamInteger has partition properties defined both in class " +
                "\"org.voltdb.compiler.procedures.PartitionParamInteger\" and in the schema defintion file(s)");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger;" +
                "PARTITION PROCEDURE NotAnnotatedPartitionParamInteger ON TABLE PKEY_WHAAAT COLUMN PKEY;"
                , "PartitionInfo for procedure " +
                "org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger refers to a column " +
                "in schema which can't be found.");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger;" +
                "PARTITION PROCEDURE NotAnnotatedPartitionParamInteger ON TABLE PKEY_INTEGER COLUMN PSURROGATE;"
                , "PartitionInfo for procedure " +
                "org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger refers to a column " +
                "in schema which can't be found.");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger;" +
                "PARTITION PROCEDURE NotAnnotatedPartitionParamInteger ON TABLE PKEY_INTEGER COLUMN PKEY PARAMETER 8;"
                , "PartitionInfo specifies invalid parameter index for procedure: " +
                "org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM GLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger;" +
                "PARTITION PROCEDURE NotAnnotatedPartitionParamInteger ON TABLE PKEY_INTEGER COLUMN PKEY;"
                , "Invalid CREATE PROCEDURE statement: " +
                "\"CREATE PROCEDURE FROM GLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger\"" +
                ", expected syntax: \"CREATE PROCEDURE");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger;" +
                "PARTITION PROCEDURE NotAnnotatedPartitionParamInteger FOR TABLE PKEY_INTEGER COLUMN PKEY;"
                , "Invalid PARTITION statement: \"PARTITION PROCEDURE " +
                "NotAnnotatedPartitionParamInteger FOR TABLE PKEY_INTEGER COLUMN PKEY\", " +
                "expected syntax: PARTITION PROCEDURE <procedure> ON " +
                "TABLE <table> COLUMN <column> [PARAMETER <parameter-index-no>]");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger;" +
                "PARTITION PROCEDURE NotAnnotatedPartitionParamInteger ON TABLE PKEY_INTEGER CLUMN PKEY PARMTR 0;"
                , "Invalid PARTITION statement: \"PARTITION PROCEDURE " +
                "NotAnnotatedPartitionParamInteger ON TABLE PKEY_INTEGER CLUMN PKEY PARMTR 0\", " +
                "expected syntax: PARTITION PROCEDURE <procedure> ON " +
                "TABLE <table> COLUMN <column> [PARAMETER <parameter-index-no>]");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger;" +
                "PARTITION PROCEDURE NotAnnotatedPartitionParamInteger ON TABLE PKEY_INTEGER COLUMN PKEY PARAMETER hello;"
                , "Invalid PARTITION statement: \"PARTITION PROCEDURE " +
                "NotAnnotatedPartitionParamInteger ON TABLE PKEY_INTEGER COLUMN PKEY PARAMETER hello\", " +
                "expected syntax: PARTITION PROCEDURE <procedure> ON " +
                "TABLE <table> COLUMN <column> [PARAMETER <parameter-index-no>]");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger;" +
                "PARTITION PROGEDURE NotAnnotatedPartitionParamInteger ON TABLE PKEY_INTEGER COLUMN PKEY PARAMETER hello;"
                , "Invalid PARTITION statement: " +
                "\"PARTITION PROGEDURE NotAnnotatedPartitionParamInteger ON TABLE PKEY_INTEGER " +
                "COLUMN PKEY PARAMETER hello\", expected syntax: \"PARTITION TABLE <table> " +
                "ON COLUMN <column>\" or \"PARTITION PROCEDURE <procedure> ON " +
                "TABLE <table> COLUMN <column> [PARAMETER <parameter-index-no>]\"");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE OUTOF CLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger;" +
                "PARTITION PROCEDURE NotAnnotatedPartitionParamInteger ON TABLE PKEY_INTEGER COLUMN PKEY PARAMETER 2;"
                , "Invalid CREATE PROCEDURE statement: " +
                "\"CREATE PROCEDURE OUTOF CLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger\"" +
                ", expected syntax: \"CREATE PROCEDURE");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "MAKE PROCEDURE OUTOF CLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger;" +
                "PARTITION PROCEDURE NotAnnotatedPartitionParamInteger ON TABLE PKEY_INTEGER COLUMN PKEY PARAMETER 2;"
                , "DDL Error: \"unexpected token: MAKE\" in statement starting on lineno: 1");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE 1PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger;" +
                "PARTITION PROCEDURE NotAnnotatedPartitionParamInteger ON TABLE PKEY_INTEGER COLUMN;"
                , "Unknown indentifier in DDL: \"PARTITION TABLE 1PKEY_INTEGER ON COLUMN PKEY\" " +
                "contains invalid identifier \"1PKEY_INTEGER\"");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN 2PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger;" +
                "PARTITION PROCEDURE NotAnnotatedPartitionParamInteger ON TABLE PKEY_INTEGER COLUMN PKEY;"
                , "Unknown indentifier in DDL: \"PARTITION TABLE PKEY_INTEGER ON COLUMN 2PKEY\" " +
                "contains invalid identifier \"2PKEY\"");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS 0rg.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger;" +
                "PARTITION PROCEDURE NotAnnotatedPartitionParamInteger ON TABLE PKEY_INTEGER COLUMN PKEY;"
                , "Unknown indentifier in DDL: \""+
                "CREATE PROCEDURE FROM CLASS 0rg.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger" +
                "\" contains invalid identifier \"0rg.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger\"");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.3compiler.procedures.NotAnnotatedPartitionParamInteger;" +
                "PARTITION PROCEDURE NotAnnotatedPartitionParamInteger ON TABLE PKEY_INTEGER COLUMN PKEY;"
                , "Unknown indentifier in DDL: \""+
                "CREATE PROCEDURE FROM CLASS org.voltdb.3compiler.procedures.NotAnnotatedPartitionParamInteger" +
                "\" contains invalid identifier \"org.voltdb.3compiler.procedures.NotAnnotatedPartitionParamInteger\"");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.4NotAnnotatedPartitionParamInteger;" +
                "PARTITION PROCEDURE NotAnnotatedPartitionParamInteger ON TABLE PKEY_INTEGER COLUMN PKEY;"
                , "Unknown indentifier in DDL: \""+
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.4NotAnnotatedPartitionParamInteger" +
                "\" contains invalid identifier \"org.voltdb.compiler.procedures.4NotAnnotatedPartitionParamInteger\"");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger;" +
                "PARTITION PROCEDURE 5NotAnnotatedPartitionParamInteger ON TABLE PKEY_INTEGER COLUMN PKEY;"
                , "Unknown indentifier in DDL: \""+
                "PARTITION PROCEDURE 5NotAnnotatedPartitionParamInteger ON TABLE PKEY_INTEGER COLUMN PKEY" +
                "\" contains invalid identifier \"5NotAnnotatedPartitionParamInteger\"");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger;" +
                "PARTITION PROCEDURE NotAnnotatedPartitionParamInteger ON TABLE 6PKEY_INTEGER COLUMN PKEY;"
                , "Unknown indentifier in DDL: \""+
                "PARTITION PROCEDURE NotAnnotatedPartitionParamInteger ON TABLE 6PKEY_INTEGER COLUMN PKEY" +
                "\" contains invalid identifier \"6PKEY_INTEGER\"");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger;" +
                "PARTITION PROCEDURE NotAnnotatedPartitionParamInteger ON TABLE PKEY_INTEGER COLUMN 7PKEY;"
                , "Unknown indentifier in DDL: \""+
                "PARTITION PROCEDURE NotAnnotatedPartitionParamInteger ON TABLE PKEY_INTEGER COLUMN 7PKEY" +
                "\" contains invalid identifier \"7PKEY\"");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE FROM CLASS org.voltdb.compiler.procedures.NotAnnotatedPartitionParamInteger;" +
                "PARTITION PROCEDURE NotAnnotatedPartitionParamInteger TABLE PKEY_INTEGER ON TABLE PKEY_INTEGER COLUMN PKEY;"
                , "Invalid PARTITION statement: \"PARTITION PROCEDURE " +
                "NotAnnotatedPartitionParamInteger TABLE PKEY_INTEGER ON TABLE PKEY_INTEGER COLUMN PKEY\", " +
                "expected syntax: PARTITION PROCEDURE <procedure> ON " +
                "TABLE <table> COLUMN <column> [PARAMETER <parameter-index-no>]");
    }

    public void testInvalidSingleStatementCreateProcedureDDL() throws Exception {
        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE Foo AS BANBALOO pkey FROM PKEY_INTEGER;" +
                "PARTITION PROCEDURE Foo ON TABLE PKEY_INTEGER COLUMN PKEY;"
                , "Failed to plan for statement (sql) BANBALOO pkey FROM PKEY_INTEGER");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE Foo AS SELEC pkey FROM PKEY_INTEGER;" +
                "PARTITION PROCEDURE Foo ON TABLE PKEY_INTEGER COLUMN PKEY PARAMETER 0;"
                , "Failed to plan for statement (sql) SELEC pkey FROM PKEY_INTEGER");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE Foo AS DELETE FROM PKEY_INTEGER WHERE PKEY = ?;" +
                "PARTITION PROCEDURE Foo ON TABLE PKEY_INTEGER COLUMN PKEY PARAMETER 2;"
                , "PartitionInfo specifies invalid parameter index for procedure: Foo");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE Foo AS DELETE FROM PKEY_INTEGER;" +
                "PARTITION PROCEDURE Foo ON TABLE PKEY_INTEGER COLUMN PKEY;"
                , "PartitionInfo specifies invalid parameter index for procedure: Foo");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE org.kanamuri.Foo AS DELETE FROM PKEY_INTEGER;" +
                "PARTITION PROCEDURE Foo ON TABLE PKEY_INTEGER COLUMN PKEY;"
                , "PartitionInfo specifies invalid parameter index for procedure: org.kanamuri.Foo");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE 7Foo AS DELETE FROM PKEY_INTEGER WHERE PKEY = ?;" +
                "PARTITION PROCEDURE 7Foo ON TABLE PKEY_INTEGER COLUMN PKEY;"
                , "Unknown indentifier in DDL: \""+
                "CREATE PROCEDURE 7Foo AS DELETE FROM PKEY_INTEGER WHERE PKEY = ?" +
                "\" contains invalid identifier \"7Foo\"");
    }

    public void testInvalidGroovyProcedureDDL() throws Exception {
        if (Float.parseFloat(System.getProperty("java.specification.version")) < 1.7) {
            return;
        }

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE Foo AS ###\n" +
                "    stmt = new SQLStmt('SELECT PKEY, DESCR FROM PKEY = ?')\n" +
                "    transactOn = { int key -> \n" +
                "        voltQueueSQL(stmt,key)\n" +
                "        voltExecuteSQL(true)\n" +
                "    }\n" +
                "### LANGUAGE GROOVY;\n" +
                "PARTITION PROCEDURE Foo ON TABLE PKEY_INTEGER COLUMN PKEY;"
                , "user lacks privilege or object not found: PKEY");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE Foo AS ###\n" +
                "    stmt = new SQLStmt('SELECT PKEY, DESCR FROM PKEY_INTEGER WHERE PKEY = ?')\n" +
                "    transactOn = { int key -> \n" +
                "        voltQueueSQL(stmt,key)\n" +
                "        voltExecuteSQL(true)\n" +
                "    \n" +
                "### LANGUAGE GROOVY;\n" +
                "PARTITION PROCEDURE Foo ON TABLE PKEY_INTEGER COLUMN PKEY;"
                , "Procedure \"Foo\" code block has syntax errors");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE Foo AS ###\n" +
                "    stmt = new SQLStmt('SELECT PKEY, DESCR FROM PKEY_INTEGER WHERE PKEY = ?')\n" +
                "    runMeInstead = { int key -> \n" +
                "        voltQueueSQL(stmt,key)\n" +
                "        voltExecuteSQL(true)\n" +
                "    }\n" +
                "### LANGUAGE GROOVY;\n" +
                "PARTITION PROCEDURE Foo ON TABLE PKEY_INTEGER COLUMN PKEY;"
                , "Procedure \"Foo\" code block does not contain the required \"transactOn\" closure");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE Foo AS ###\n" +
                "package voltkv.procedures;\n" +
                "\n" +
                "import org.voltdb.*;\n" +
                "\n" +
                "@ProcInfo(partitionInfo=\"store.key:0\", singlePartition=true)\n" +
                "public class Put extends VoltProcedure {\n" +
                "    // Checks if key exists\n" +
                "    public final SQLStmt checkStmt = new SQLStmt(\"SELECT key FROM store WHERE key = ?;\");\n" +
                "    // Updates a key/value pair\n" +
                "    public final SQLStmt updateStmt = new SQLStmt(\"UPDATE store SET value = ? WHERE key = ?;\");\n" +
                "    // Inserts a key/value pair\n" +
                "    public final SQLStmt insertStmt = new SQLStmt(\"INSERT INTO store (key, value) VALUES (?, ?);\");\n" +
                "\n" +
                "    public VoltTable[] run(String key, byte[] value) {\n" +
                "        // Check whether the pair exists\n" +
                "        voltQueueSQL(checkStmt, key);\n" +
                "        // Insert new or update existing key depending on result\n" +
                "        if (voltExecuteSQL()[0].getRowCount() == 0)\n" +
                "            voltQueueSQL(insertStmt, key, value);\n" +
                "        else\n" +
                "            voltQueueSQL(updateStmt, value, key);\n" +
                "        return voltExecuteSQL(true);\n" +
                "    }\n" +
                "}\n" +
                "### LANGUAGE GROOVY;\n"
                , "Procedure \"voltkv.procedures.Put\" is not a groovy script");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE Foo AS ###\n" +
                "    stmt = new SQLStmt('SELECT PKEY, DESCR FROM PKEY_INTEGER WHERE PKEY = ?')\n" +
                "    transactOn = 'Is it me that you wanted instead?'\n" +
                "### LANGUAGE GROOVY;\n" +
                "PARTITION PROCEDURE Foo ON TABLE PKEY_INTEGER COLUMN PKEY;"
                , "Procedure \"Foo\" code block does not contain the required \"transactOn\" closure");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE Foo AS ###\n" +
                "    // ###\n" +
                "    stmt = new SQLStmt('SELECT PKEY, DESCR FROM PKEY_INTEGER WHERE PKEY = ?')\n" +
                "    transactOn = { int key -> \n" +
                "        voltQueueSQL(stmt,key)\n" +
                "        voltExecuteSQL(true)\n" +
                "    }\n" +
                "### LANGUAGE GROOVY;\n" +
                "PARTITION PROCEDURE Foo ON TABLE PKEY_INTEGER COLUMN PKEY;"
                , "Schema file ended mid-statement (no semicolon found)");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE Foo AS ##\n" +
                "    stmt = new SQLStmt('SELECT PKEY, DESCR FROM PKEY_INTEGER WHERE PKEY = ?')\n" +
                "    transactOn = { int key -> \n" +
                "        voltQueueSQL(stmt,key)\n" +
                "        voltExecuteSQL(true)\n" +
                "    }\n" +
                "### LANGUAGE GROOVY;\n" +
                "PARTITION PROCEDURE Foo ON TABLE PKEY_INTEGER COLUMN PKEY;"
                , "Schema file ended mid-statement (no semicolon found)");

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE Foo AS ###\n" +
                "    stmt = new SQLStmt('SELECT PKEY, DESCR FROM PKEY_INTEGER WHERE PKEY = ?')\n" +
                "    transactOn = { int key -> \n" +
                "        voltQueueSQL(stmt,key)\n" +
                "        voltExecuteSQL(true)\n" +
                "    }\n" +
                "### LANGUAGE KROOVY;\n" +
                "PARTITION PROCEDURE Foo ON TABLE PKEY_INTEGER COLUMN PKEY;"
                , "### LANGUAGE KROOVY\", expected syntax: \"CREATE PROCEDURE [ALLOW");
    }

    public void testValidGroovyProcedureDDL() throws Exception {
        if (Float.parseFloat(System.getProperty("java.specification.version")) < 1.7) return;

        Database db = goodDDLAgainstSimpleSchema(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE Foo AS ###\n" +
                "    stmt = new SQLStmt('SELECT PKEY, DESCR FROM PKEY_INTEGER WHERE PKEY = ?')\n" +
                "    transactOn = { int key -> \n" +
                "        voltQueueSQL(stmt,key)\n" +
                "        voltExecuteSQL(true)\n" +
                "    }\n" +
                "### LANGUAGE GROOVY;\n" +
                "PARTITION PROCEDURE Foo ON TABLE PKEY_INTEGER COLUMN PKEY;"
                );
        Procedure proc = db.getProcedures().get("Foo");
        assertNotNull(proc);

        db = goodDDLAgainstSimpleSchema(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE Foo AS ###\n" +
                "    // #\n" +
                "    // ##\n" +
                "    stmt = new SQLStmt('SELECT PKEY, DESCR FROM PKEY_INTEGER WHERE PKEY = ?')\n" +
                "    transactOn = { int key -> \n" +
                "        def str = '# ## # ##'\n" +
                "        voltQueueSQL(stmt,key)\n" +
                "        voltExecuteSQL(true)\n" +
                "    }\n" +
                "### LANGUAGE GROOVY;\n" +
                "PARTITION PROCEDURE Foo ON TABLE PKEY_INTEGER COLUMN PKEY;"
                );
        proc = db.getProcedures().get("Foo");
        assertNotNull(proc);

        db = goodDDLAgainstSimpleSchema(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE   \n" +
                "PROCEDURE     Foo    \n" +
                "  AS   \n" +
                "###\n" +
                "    stmt = new SQLStmt('SELECT PKEY, DESCR FROM PKEY_INTEGER WHERE PKEY = ?')\n" +
                "    transactOn = { int key -> \n" +
                "        voltQueueSQL(stmt,key)\n" +
                "        voltExecuteSQL(true)\n" +
                "    }\n" +
                "###\n" +
                "   LANGUAGE   \n" +
                "GROOVY;\n" +
                "PARTITION PROCEDURE Foo ON TABLE PKEY_INTEGER COLUMN PKEY;"
                );
        proc = db.getProcedures().get("Foo");
        assertNotNull(proc);
    }

    public void testDropProcedure() throws Exception {
        if (Float.parseFloat(System.getProperty("java.specification.version")) < 1.7) return;

        // Make sure we can drop a GROOVY procedure
        Database db = goodDDLAgainstSimpleSchema(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "CREATE PROCEDURE Foo AS ###\n" +
                "    stmt = new SQLStmt('SELECT PKEY, DESCR FROM PKEY_INTEGER WHERE PKEY = ?')\n" +
                "    transactOn = { int key -> \n" +
                "        voltQueueSQL(stmt,key)\n" +
                "        voltExecuteSQL(true)\n" +
                "    }\n" +
                "### LANGUAGE GROOVY;\n" +
                "PARTITION PROCEDURE Foo ON TABLE PKEY_INTEGER COLUMN PKEY;\n" +
                "DROP PROCEDURE Foo;"
                );
        Procedure proc = db.getProcedures().get("Foo");
        assertNull(proc);

        // Make sure we can drop a non-annotated stored procedure
        db = goodDDLAgainstSimpleSchema(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "creAte PrOcEdUrE FrOm CLasS org.voltdb.compiler.procedures.AddBook; " +
                "create procedure from class org.voltdb.compiler.procedures.NotAnnotatedAddBook; " +
                "DROP PROCEDURE org.voltdb.compiler.procedures.AddBook;"
                );
        proc = db.getProcedures().get("AddBook");
        assertNull(proc);
        proc = db.getProcedures().get("NotAnnotatedAddBook");
        assertNotNull(proc);

        // Make sure we can drop an annotated stored procedure
        db = goodDDLAgainstSimpleSchema(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "creAte PrOcEdUrE FrOm CLasS org.voltdb.compiler.procedures.AddBook; " +
                "create procedure from class org.voltdb.compiler.procedures.NotAnnotatedAddBook; " +
                "DROP PROCEDURE NotAnnotatedAddBook;"
                );
        proc = db.getProcedures().get("NotAnnotatedAddBook");
        assertNull(proc);
        proc = db.getProcedures().get("AddBook");
        assertNotNull(proc);

        // Make sure we can drop a single-statement procedure
        db = goodDDLAgainstSimpleSchema(
                "create procedure p1 as select * from books;\n" +
                "drop procedure p1;"
                );
        proc = db.getProcedures().get("p1");
        assertNull(proc);

        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "creAte PrOcEdUrE FrOm CLasS org.voltdb.compiler.procedures.AddBook; " +
                "DROP PROCEDURE NotAnnotatedAddBook;"
                , "Dropped Procedure \"NotAnnotatedAddBook\" is not defined");

        // Make sure we can't drop a CRUD procedure (full name)
        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "DROP PROCEDURE PKEY_INTEGER.insert;"
                , "Dropped Procedure \"PKEY_INTEGER.insert\" is not defined");

        // Make sure we can't drop a CRUD procedure (partial name)
        checkDDLErrorMessage(
                "CREATE TABLE PKEY_INTEGER ( PKEY INTEGER NOT NULL, DESCR VARCHAR(128), PRIMARY KEY (PKEY) );" +
                "PARTITION TABLE PKEY_INTEGER ON COLUMN PKEY;" +
                "DROP PROCEDURE insert;"
                , "Dropped Procedure \"insert\" is not defined");

        // check if exists
        db = goodDDLAgainstSimpleSchema(
                "create procedure p1 as select * from books;\n" +
                "drop procedure p1 if exists;\n" +
                "drop procedure p1 if exists;\n"
                );
        proc = db.getProcedures().get("p1");
        assertNull(proc);
    }

    public void testValidAnnotatedProcedureDLL() throws Exception {
        final String simpleSchema =
                "create table books (cash integer default 23 not null, title varchar(3) default 'foo', PRIMARY KEY(cash));" +
                "PARTITION TABLE books ON COLUMN cash;" +
                "creAte PrOcEdUrE FrOm CLasS org.voltdb.compiler.procedures.AddBook;";

        compileValidLiteralSchema(simpleSchema);

        final Catalog c2 = VoltCompilerUtils.deserializeCatalogFromCatalogJarfile(testout_jar);
        final Database db = CatalogUtil.getDatabase(c2);
        final Procedure addBook = db.getProcedures().get("AddBook");
        assertEquals(true, addBook.getSinglepartition());
    }

    public void testValidNonAnnotatedProcedureDDL() throws Exception {
        final String simpleSchema =
                "create table books (cash integer default 23 not null, title varchar(3) default 'foo', PRIMARY KEY(cash));" +
                "PARTITION TABLE books ON COLUMN cash;" +
                "create procedure from class org.voltdb.compiler.procedures.NotAnnotatedAddBook;" +
                "paRtItiOn prOcEdure NotAnnotatedAddBook On taBLe   books coLUmN cash   ParaMETer  0;";

        compileValidLiteralSchema(simpleSchema);
        final Catalog c2 = VoltCompilerUtils.deserializeCatalogFromCatalogJarfile(testout_jar);
        final Database db = CatalogUtil.getDatabase(c2);
        final Procedure addBook = db.getProcedures().get("NotAnnotatedAddBook");
        assertEquals(true, addBook.getSinglepartition());
    }

    class TestRole {
        final String name;
        boolean sql = false;
        boolean sqlread = false;
        boolean sysproc = false;
        boolean defaultproc = false;
        boolean defaultprocread = false;
        boolean allproc = false;

        public TestRole(String name) {
            this.name = name;
        }

        public TestRole(String name, boolean sql, boolean sqlread, boolean sysproc,
                        boolean defaultproc, boolean defaultprocread, boolean allproc) {
            this.name = name;
            this.sql = sql;
            this.sqlread = sqlread;
            this.sysproc = sysproc;
            this.defaultproc = defaultproc;
            this.defaultprocread = defaultprocread;
            this.allproc = allproc;
        }
    }

    private void checkRoleXMLAndDDL(String rolesElem, String ddl, String errorRegex, TestRole... roles) throws Exception {
        final String schemaPath = MiscUtils.writeStringToTempFilePath(ddl != null ? ddl : "");
        String rolesBlock = (rolesElem != null ? String.format("<roles>%s</roles>", rolesElem) : "");

        final String simpleProject =
            "<?xml version=\"1.0\"?>\n" +
            "<project>" +
            "<database name='database'>" +
            "<schemas>" +
            "<schema path='" + schemaPath + "' />" +
            "</schemas>" +
            rolesBlock +
            "<procedures/>" +
            "</database>" +
            "</project>";

        final String projectPath = MiscUtils.writeStringToTempFilePath(simpleProject);

        final VoltCompiler compiler = new VoltCompiler();

        final boolean success = compiler.compileWithProjectXML(projectPath, testout_jar);
        String error = (success || compiler.m_errors.size() == 0
                            ? ""
                            : compiler.m_errors.get(compiler.m_errors.size()-1).message);
        if (errorRegex == null) {
            checkRoles(rolesElem, ddl, compiler, success, error, roles);
        }
        else {
            assertFalse(String.format("Expected error (\"%s\")\nXML: %s\nDDL: %s", errorRegex, rolesElem, ddl), success);
            assertFalse("Expected at least one error message.", error.isEmpty());
            Matcher m = Pattern.compile(errorRegex).matcher(error);
            assertTrue(String.format("%s\nEXPECTED: %s", error, errorRegex), m.matches());
        }
    }

    /**
     * @param rolesElem
     * @param ddl
     * @param compiler
     * @param success
     * @param error
     * @param roles
     */
    private void checkRoles(String rolesElem, String ddl,
            final VoltCompiler compiler, final boolean success, String error,
            TestRole... roles) {
        assertTrue(String.format("Expected success\nXML: %s\nDDL: %s\nERR: %s", rolesElem, ddl, error), success);

        Database db = compiler.getCatalogDatabase();
        CatalogMap<Group> groups = db.getGroups();
        CatalogMap<Connector> connectors = db.getConnectors();
        if (connectors.get("0") == null ) {
            connectors.add("0");
        }

        assertNotNull(groups);
        assertTrue(roles.length <= groups.size());

        for (TestRole role : roles) {
            Group group = groups.get(role.name);
            assertNotNull(String.format("Missing role \"%s\"", role.name), group);
            assertEquals(String.format("Role \"%s\" sql flag mismatch:", role.name), role.sql, group.getSql());
            assertEquals(String.format("Role \"%s\" sqlread flag mismatch:", role.name), role.sqlread, group.getSqlread());
            assertEquals(String.format("Role \"%s\" admin flag mismatch:", role.name), role.sysproc, group.getAdmin());
            assertEquals(String.format("Role \"%s\" defaultproc flag mismatch:", role.name), role.defaultproc, group.getDefaultproc());
            assertEquals(String.format("Role \"%s\" defaultprocread flag mismatch:", role.name), role.defaultprocread, group.getDefaultprocread());
            assertEquals(String.format("Role \"%s\" allproc flag mismatch:", role.name), role.allproc, group.getAllproc());
        }
    }

    private void goodRoleDDL(String ddl, TestRole... roles) throws Exception {
        checkRoleXMLAndDDL(null, ddl, null, roles);
    }

    private void badRoleDDL(String ddl, String errorRegex) throws Exception {
        checkRoleXMLAndDDL(null, ddl, errorRegex);
    }

    public void testRoleDDL() throws Exception {
        goodRoleDDL("create role R1;", new TestRole("r1"));
        goodRoleDDL("create role r1;create role r2;", new TestRole("r1"), new TestRole("R2"));
        goodRoleDDL("create role r1 with adhoc;", new TestRole("r1", true, true, false, true, true, false));
        goodRoleDDL("create role r1 with sql;", new TestRole("r1", true, true, false, true, true, false));
        goodRoleDDL("create role r1 with sqlread;", new TestRole("r1", false, true, false, false, true, false));
        goodRoleDDL("create role r1 with sysproc;", new TestRole("r1", true, true, true, true, true, true));
        goodRoleDDL("create role r1 with defaultproc;", new TestRole("r1", false, false, false, true, true, false));
        goodRoleDDL("create role r1 with adhoc,sysproc,defaultproc;", new TestRole("r1", true, true, true, true, true, true));
        goodRoleDDL("create role r1 with adhoc,sysproc,sysproc;", new TestRole("r1", true, true, true, true, true, true));
        goodRoleDDL("create role r1 with AdHoc,SysProc,DefaultProc;", new TestRole("r1", true, true, true, true, true, true));
        //Defaultprocread.
        goodRoleDDL("create role r1 with defaultprocread;", new TestRole("r1", false, false, false, false, true, false));
        goodRoleDDL("create role r1 with AdHoc,SysProc,DefaultProc,DefaultProcRead;", new TestRole("r1", true, true, true, true, true, true));
        goodRoleDDL("create role r1 with AdHoc,Admin,DefaultProc,DefaultProcRead;", new TestRole("r1", true, true, true, true, true, true));
        goodRoleDDL("create role r1 with allproc;", new TestRole("r1", false, false, false, false, false, true));

        // Check default roles: ADMINISTRATOR, USER
        goodRoleDDL("",
                    new TestRole("ADMINISTRATOR", true, true, true, true, true, true),
                    new TestRole("USER", true, true, false, true, true, true));
    }

    public void testBadRoleDDL() throws Exception {
        badRoleDDL("create role r1", ".*no semicolon.*");
        badRoleDDL("create role r1;create role r1;", ".*already exists.*");
        badRoleDDL("create role r1 with ;", ".*Invalid CREATE ROLE statement.*");
        badRoleDDL("create role r1 with blah;", ".*Invalid permission \"BLAH\".*");
        badRoleDDL("create role r1 with adhoc sysproc;", ".*Invalid CREATE ROLE statement.*");
        badRoleDDL("create role r1 with adhoc, blah;", ".*Invalid permission \"BLAH\".*");

        // cannot override default roles
        badRoleDDL("create role ADMINISTRATOR;", ".*already exists.*");
        badRoleDDL("create role USER;", ".*already exists.*");
    }

    private VoltCompiler checkDDLAgainstSimpleSchema(String errorSubstr, String... ddl) throws Exception {
        String schemaDDL =
            "create table books (cash integer default 23 NOT NULL, title varbinary(10) default NULL, PRIMARY KEY(cash)); " +
            "partition table books on column cash;" +
            StringUtils.join(ddl, " ");
        return checkDDLErrorMessage(schemaDDL, errorSubstr);
    }

    private Database goodDDLAgainstSimpleSchema(String... ddl) throws Exception {
        VoltCompiler compiler = checkDDLAgainstSimpleSchema(null, ddl);
        return compiler.getCatalogDatabase();
    }

    private void badDDLAgainstSimpleSchema(String errorSubstr, String... ddl) throws Exception {
        checkDDLAgainstSimpleSchema(errorSubstr, ddl);
    }

    public void testGoodCreateProcedureWithAllow() throws Exception {
        Database db = goodDDLAgainstSimpleSchema(
                "create role r1;",
                "create procedure p1 allow r1 as select * from books;");
        Procedure proc = db.getProcedures().get("p1");
        assertNotNull(proc);
        CatalogMap<GroupRef> groups = proc.getAuthgroups();
        assertEquals(1, groups.size());
        assertNotNull(groups.get("r1"));

        db = goodDDLAgainstSimpleSchema(
                "create role r1;",
                "create role r2;",
                "create procedure p1 allow r1, r2 as select * from books;");
        proc = db.getProcedures().get("p1");
        assertNotNull(proc);
        groups = proc.getAuthgroups();
        assertEquals(2, groups.size());
        assertNotNull(groups.get("r1"));
        assertNotNull(groups.get("r2"));

        db = goodDDLAgainstSimpleSchema(
                "create role r1;",
                "create procedure allow r1 from class org.voltdb.compiler.procedures.AddBook;");
        proc = db.getProcedures().get("AddBook");
        assertNotNull(proc);
        groups = proc.getAuthgroups();
        assertEquals(1, groups.size());
        assertNotNull(groups.get("r1"));

        db = goodDDLAgainstSimpleSchema(
                "create role r1;",
                "create role r2;",
                "create procedure allow r1,r2 from class org.voltdb.compiler.procedures.AddBook;");
        proc = db.getProcedures().get("AddBook");
        assertNotNull(proc);
        groups = proc.getAuthgroups();
        assertEquals(2, groups.size());
        assertNotNull(groups.get("r1"));
        assertNotNull(groups.get("r2"));

        db = goodDDLAgainstSimpleSchema(
                "create role r1;",
                "create procedure allow r1,r1 from class org.voltdb.compiler.procedures.AddBook;");
        proc = db.getProcedures().get("AddBook");
        assertNotNull(proc);
        groups = proc.getAuthgroups();
        assertEquals(1, groups.size());
        assertNotNull(groups.get("r1"));
    }

    public void testBadCreateProcedureWithAllow() throws Exception {
        badDDLAgainstSimpleSchema("expected syntax",
                "create procedure p1 allow as select * from books;");
        badDDLAgainstSimpleSchema("expected syntax",
                "create procedure p1 allow a b as select * from books;");
        badDDLAgainstSimpleSchema("group rx that does not exist",
                "create procedure p1 allow rx as select * from books;");
        badDDLAgainstSimpleSchema("group rx that does not exist",
                "create role r1;",
                "create procedure p1 allow r1, rx as select * from books;");
    }

    public void testDropRole() throws Exception
    {
        Database db = goodDDLAgainstSimpleSchema(
                "create role r1;",
                "drop role r1;");
        CatalogMap<Group> groups = db.getGroups();
        assertTrue(groups.get("r1") == null);

        db = goodDDLAgainstSimpleSchema(
                "create role r1;",
                "drop role r1 if exists;");
        groups = db.getGroups();
        assertTrue(groups.get("r1") == null);

        db = goodDDLAgainstSimpleSchema(
                "create role r1;",
                "drop role r1 if exists;",
                "drop role r1 IF EXISTS;");
        groups = db.getGroups();
        assertTrue(groups.get("r1") == null);

        badDDLAgainstSimpleSchema("does not exist",
                "create role r1;",
                "drop role r2;");

        badDDLAgainstSimpleSchema("does not exist",
                "create role r1;",
                "drop role r1;",
                "drop role r1;");

        badDDLAgainstSimpleSchema("may not drop",
                "drop role administrator;");

        badDDLAgainstSimpleSchema("may not drop",
                "drop role user;");
    }

    private ConnectorTableInfo getConnectorTableInfoFor( Database db, String tableName) {
        Connector connector =  db.getConnectors().get("0");
        if( connector == null) return null;
        return connector.getTableinfo().getIgnoreCase(tableName);
    }

    public void testGoodExportTable() throws Exception {
        Database db;

        db = goodDDLAgainstSimpleSchema(
                "create table e1 (id integer, f1 varchar(16));",
                "export table e1;"
                );
        assertNotNull(getConnectorTableInfoFor(db, "e1"));

        db = goodDDLAgainstSimpleSchema(
                "create table e1 (id integer, f1 varchar(16));",
                "create table e2 (id integer, f1 varchar(16));",
                "export table e1;",
                "eXpOrt TABle E2;"
                );
        assertNotNull(getConnectorTableInfoFor(db, "e1"));
        assertNotNull(getConnectorTableInfoFor(db, "e2"));
    }

    public void testBadExportTable() throws Exception {

        badDDLAgainstSimpleSchema("EXPORT statement: table non_existant was not present in the catalog",
                "export table non_existant;"
                );

        badDDLAgainstSimpleSchema("contains invalid identifier \"1table_name_not_valid\"",
                "export table 1table_name_not_valid;"
                );

        badDDLAgainstSimpleSchema("Invalid EXPORT TABLE statement",
                "export table one, two, three;"
                );

        badDDLAgainstSimpleSchema("Invalid EXPORT TABLE statement",
                "export export table one;"
                );

        badDDLAgainstSimpleSchema("Invalid EXPORT TABLE statement",
                "export table table one;"
                );

        badDDLAgainstSimpleSchema("Table with indexes configured as an export table",
                "export table books;"
                );

        badDDLAgainstSimpleSchema("Export table configured with materialized view",
                "create table view_source( id integer, f1 varchar(16), f2 varchar(12));",
                "create view my_view as select f2, count(*) as f2cnt from view_source group by f2;",
                "export table view_source;"
                );

        badDDLAgainstSimpleSchema("View configured as an export table",
                "create table view_source( id integer, f1 varchar(16), f2 varchar(12));",
                "create view my_view as select f2, count(*) as f2cnt from view_source group by f2;",
                "export table my_view;"
                );
    }

    public void testCompileFromDDL() throws IOException {
        final String simpleSchema1 =
            "create table table1r_el  (pkey integer, column2_integer integer, PRIMARY KEY(pkey));\n" +
            "create view v_table1r_el (column2_integer, num_rows) as\n" +
            "select column2_integer as column2_integer,\n" +
                "count(*) as num_rows\n" +
            "from table1r_el\n" +
            "group by column2_integer;\n" +
            "create view v_table1r_el2 (column2_integer, num_rows) as\n" +
            "select column2_integer as column2_integer,\n" +
                "count(*) as num_rows\n" +
            "from table1r_el\n" +
            "group by column2_integer\n;\n";

        final String schemaPath = MiscUtils.writeStringToTempFilePath(simpleSchema1);

        final VoltCompiler compiler = new VoltCompiler();

        assertTrue(compileFromDDL(compiler, schemaPath));

        assertFalse(compileFromDDL(compiler, schemaPath + "???"));

        // Trivially test that VoltCompiler politely refuses to compile 0 schema files.
        // Should we care?
        try {
            assertFalse(compiler.compileFromDDL(testout_jar));
        }
        catch (VoltCompilerException e) {
            e.printStackTrace();
            fail();
        }
    }

    private int countStringsMatching(List<String> diagnostics, String pattern) {
        int count = 0;
        for (String string : diagnostics) {
            if (string.matches(pattern)) {
                ++count;
            }
        }
        return count;
    }

}
