package be.ugent.rml.records;

import be.ugent.rml.NAMESPACES;
import be.ugent.rml.Utils;
import be.ugent.rml.access.Access;
import be.ugent.rml.store.QuadStore;
import be.ugent.rml.term.Literal;
import be.ugent.rml.term.NamedNode;
import be.ugent.rml.term.Term;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.io.FilenameUtils;
import org.odftoolkit.simple.Document;
import org.odftoolkit.simple.SpreadsheetDocument;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This class is a record factory that creates CSV records.
 */
public class CSVRecordFactory implements ReferenceFormulationRecordFactory {
    private static final Logger logger = LoggerFactory.getLogger(CSVRecordFactory.class);

    /**
     * This method returns a list of CSV records for a data source.
     *
     * @param access        the access from which records need to be fetched.
     * @param logicalSource the used Logical Source.
     * @param rmlStore      the QuadStore with the RML rules.
     * @return a list of records.
     * @throws IOException
     */
    @Override
    public List<Record> getRecords(Access access, Term logicalSource, QuadStore rmlStore) throws Exception {
        List<Term> sources = Utils.getObjectsFromQuads(rmlStore.getQuads(logicalSource, new NamedNode(NAMESPACES.RML + "source"), null));
        Term source = sources.get(0);

        if (source instanceof Literal) {
            // We are not dealing with something like CSVW.
            // Check for different spreadsheet formats
            String filePath = source.getValue();
            String extension = FilenameUtils.getExtension(filePath);
            switch (extension) {
                case "xlsx":
                    return getRecordsForExcel(access);
                case "ods":
                    return getRecordsForODT(access);
                default:
                    return getRecordsForCSV(access, null);
            }

        } else {
            List<Term> sourceType = Utils.getObjectsFromQuads(rmlStore.getQuads(source, new NamedNode(NAMESPACES.RDF + "type"), null));

            // Check if we are dealing with CSVW.
            if (sourceType.get(0).getValue().equals(NAMESPACES.CSVW + "Table")) {
                CSVW csvw = new CSVW(access.getInputStream(), rmlStore, logicalSource);
                return getRecordsForCSV(access, csvw);
            } else {
                // RDBs fall under this.
                return getRecordsForCSV(access, null);
            }
        }
    }

    /**
     * Get Records for Excel file format.
     * @param access
     * @return
     * @throws IOException
     */
    private List<Record> getRecordsForExcel(Access access) throws IOException, SQLException, ClassNotFoundException {
        List<Record> output = new ArrayList<>();
        Workbook workbook = new XSSFWorkbook(access.getInputStream());
        for (Sheet datatypeSheet : workbook) {
            Row header = datatypeSheet.getRow(0);
            boolean first = true;
            for (Row currentRow : datatypeSheet) {
                // remove the header
                if (first) {
                    first = false;
                } else {
                    output.add(new ExcelRecord(header, currentRow));
                }
            }
        }
        workbook.close();
        return output;
    }

    /**
     * Get Records for ODT file format.
     * @param access
     * @return
     * @throws IOException
     */
    private List<Record> getRecordsForODT(Access access) throws Exception {
        List<Record> output = new ArrayList<>();
        InputStream is = access.getInputStream();
        Document document = SpreadsheetDocument.loadDocument(is);
        for (org.odftoolkit.simple.table.Table table : document.getTableList()) {
            org.odftoolkit.simple.table.Row header = table.getRowByIndex(0);
            boolean first = true;
            for (org.odftoolkit.simple.table.Row currentRow : table.getRowList()) {
                if (first) {
                    first = false;
                } else {
                    output.add(new ODSRecord(header, currentRow));
                }
            }
        }
        return output;
    }

    /**
     * This method returns a CSVParser from a simple access (local/remote CSV file; no CSVW).
     *
     * @param access the used access.
     * @return a CSVParser.
     * @throws IOException
     */
    private List<Record> getRecordsForCSV(Access access, CSVW csvw) throws IOException, SQLException, ClassNotFoundException {
        CSVParser parser;
        // Check if we are dealing with CSVW.
        if (csvw != null) {
            parser = csvw.getCSVParser();
        } else {
            // RDBs fall under this.
            CSVFormat csvFormat = CSVFormat.DEFAULT.withHeader().withSkipHeaderRecord(false).withNullString("@@@@NULL@@@@");
            InputStream inputStream = access.getInputStream();

            try {
                parser = CSVParser.parse(inputStream, StandardCharsets.UTF_8, csvFormat);
            } catch (IllegalArgumentException e) {
                logger.debug("Could not parse CSV inputstream", e);
                parser = null;
            }
        }

        if (parser != null) {
            List<org.apache.commons.csv.CSVRecord> myEntries = parser.getRecords();

            return myEntries.stream()
                    .map(record -> new CSVRecord(record, access.getDataTypes()))
                    .collect(Collectors.toList());
        } else {
            // We still return an empty list of records when a parser is not found.
            // This is to support certain use cases with RDBs where queries might not be valid,
            // but you don't want the RMLMapper to crash.
            return new ArrayList<>();
        }
    }
}
