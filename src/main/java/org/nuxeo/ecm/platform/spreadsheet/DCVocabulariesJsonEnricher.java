package org.nuxeo.ecm.platform.spreadsheet;

import static org.nuxeo.ecm.core.io.registry.reflect.Instantiations.SINGLETON;
import static org.nuxeo.ecm.core.io.registry.reflect.Priorities.REFERENCE;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonGenerator;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.model.Property;
import org.nuxeo.ecm.core.api.model.PropertyNotFoundException;
import org.nuxeo.ecm.core.io.marshallers.json.enrichers.AbstractJsonEnricher;
import org.nuxeo.ecm.core.io.registry.reflect.Setup;
import org.nuxeo.ecm.core.schema.SchemaManager;
import org.nuxeo.ecm.core.schema.types.Field;
import org.nuxeo.ecm.core.schema.types.Schema;
import org.nuxeo.ecm.directory.DirectoryException;
import org.nuxeo.ecm.directory.Session;
import org.nuxeo.ecm.directory.api.DirectoryService;

@Setup(mode = SINGLETON, priority = REFERENCE)
public class DCVocabulariesJsonEnricher extends AbstractJsonEnricher<DocumentModel> {

    private static final Log log = LogFactory.getLog(DCVocabulariesJsonEnricher.class);

    public static final String NAME = "vocabularies";

    private static final String DIRECTORY_DEFAULT_LABEL_PREFIX = "label_";

    private static final String KEY_SEPARATOR = "/";

    @Inject
    private DirectoryService directoryService;

    @Inject
    private SchemaManager schemaManager;

    public DCVocabulariesJsonEnricher() {
        super(NAME);
    }

    @Override
    public void write(JsonGenerator jg, DocumentModel document) throws IOException {
        writeVocabulary(jg, document, "l10nsubjects", "dc:subjects");
        writeVocabulary(jg, document, "l10ncoverage", "dc:coverage");
    }

    private void writeVocabulary(JsonGenerator jg, final DocumentModel doc, String directoryName, String fieldName)
            throws IOException, JsonGenerationException {
        try {
            // Lookup directory schema to find label columns
            List<String> labelFields = getLabelFields(directoryName);
            // Get the field values
            String[] entriesIds = getPropertyValues(doc, fieldName);
            // 'field': [
            jg.writeFieldName(fieldName);
            jg.writeStartArray();
            // { 'id': ..., 'label_*': ... }
            if (entriesIds != null) {
                writeLabels(jg, directoryName, entriesIds, labelFields);
            }
            // ]
            jg.writeEndArray();
        } catch (PropertyNotFoundException | DirectoryException e) {
            log.error(e.getMessage());
        }
    }

    /**
     * Writes the labels for each entry
     *
     * @param jg
     * @param directoryName
     * @param entriesIds
     * @param labelFields
     * @throws IOException
     */
    private void writeLabels(JsonGenerator jg, String directoryName, String[] entriesIds, List<String> labelFields)
            throws IOException {
        Session session = null;
        try {
            session = directoryService.open(directoryName);
            for (String entryId : entriesIds) {
                Map<String, String> labels = getAbsoluteLabels(entryId, session, labelFields);
                // Write absolute labels (<parent label> / <child label>)
                jg.writeStartObject();
                jg.writeStringField("id", entryId);
                for (Map.Entry<String, String> label : labels.entrySet()) {
                    jg.writeStringField(label.getKey(), label.getValue());
                }
                jg.writeEndObject();
            }
        } finally {
            if (session != null) {
                session.close();
            }
        }

    }

    /**
     * Determines label columns based on the label prefix
     *
     * @param directoryName the name of the directory to inspect
     * @return
     */
    private List<String> getLabelFields(String directoryName) {
        String schemaName = directoryService.getDirectorySchema(directoryName);
        Schema schema = schemaManager.getSchema(schemaName);
        List<String> labelFields = new ArrayList<String>();
        String fieldName;
        for (Field field : schema.getFields()) {
            fieldName = field.getName().toString();
            if (fieldName.startsWith(DIRECTORY_DEFAULT_LABEL_PREFIX)) {
                labelFields.add(fieldName);
            }
        }
        return labelFields;
    }

    /**
     * Return the values of a document's property as an array of strings
     *
     * @param doc
     * @param fieldName
     * @return
     */
    private static String[] getPropertyValues(DocumentModel doc, String fieldName) {
        String[] entriesIds = null;
        Property prop = doc.getProperty(fieldName);
        if (prop.isList()) {
            entriesIds = prop.getValue(String[].class);
        } else {
            String value = prop.getValue(String.class);
            if (value != null) {
                entriesIds = new String[] { value };
            }
        }
        return entriesIds;
    }

    /**
     * Returns absolute labels for a given entry (<parent label> / <child label>)
     *
     * @param entryId
     * @param session
     * @param labelFields
     * @return a map of field: label
     * @throws ClientException
     */
    private static Map<String, String> getAbsoluteLabels(final String entryId, final Session session,
            List<String> labelFields) throws ClientException {
        String[] split = entryId.split(KEY_SEPARATOR);
        Map<String, String> labels = new HashMap<>();
        for (int i = 0; i < split.length; i++) {
            DocumentModel entry = session.getEntry(split[i]);
            if (entry == null) {
                continue;
            }
            for (String labelField : labelFields) {
                String result = labels.get(labelField);
                if (result == null) {
                    result = "";
                }
                String value = (String) entry.getPropertyValue(labelField);
                result += (i > 0 ? "/" : "") + value;
                labels.put(labelField, result);
            }
        }
        return labels;
    }

}
