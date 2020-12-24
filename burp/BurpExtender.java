package burp;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.FlowLayout;
import java.awt.Font;
import java.lang.StringBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.text.DefaultCaret;
import javax.swing.SwingUtilities;

// https://github.com/eiiches/jackson-jq
// https://github.com/FasterXML
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import net.thisptr.jackson.jq.BuiltinFunctionLoader;
import net.thisptr.jackson.jq.JsonQuery;
import net.thisptr.jackson.jq.Scope;
import net.thisptr.jackson.jq.Version;
import net.thisptr.jackson.jq.Versions;


public class BurpExtender implements IBurpExtender, IMessageEditorTabFactory {

    private IBurpExtenderCallbacks callbacks;
    private IExtensionHelpers helpers;
    private static final Color colorError = new Color(247, 66, 62);
    private static final Color colorOK = new Color(81, 179, 100);
    private static final Color colorWarning = new Color(252, 151, 78);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Scope scope = Scope.newEmptyScope();
    private static final String COMMIT_ACTION = "commit";
    private static final Version jqVersion = Versions.JQ_1_6;

    @Override
    public void registerExtenderCallbacks(IBurpExtenderCallbacks callbacks) {

        this.callbacks = callbacks;
        helpers = callbacks.getHelpers();
        callbacks.setExtensionName("JQ");

        // UI
        callbacks.registerMessageEditorTabFactory(BurpExtender.this);

        // Jackson JQ
        DefaultPrettyPrinter prettyPrinter = new DefaultPrettyPrinter();
        prettyPrinter.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
        mapper.setDefaultPrettyPrinter(prettyPrinter);
        mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
        BuiltinFunctionLoader.getInstance().loadFunctions(jqVersion, scope);
    }

    class JQTab implements IMessageEditorTab {

        private ITextEditor outputArea;
        private JCheckBox checkBoxFilterOutNulls;
        private JCheckBox checkBoxKeys;
        private JCheckBox checkBoxPretty;
        private JCheckBox checkBoxRaw;
        private JCheckBox checkBoxSort;
        private JCheckBox checkBoxUnique;
        private JPanel container = new JPanel(new BorderLayout());
        private JqComplete jqComplete;
        private JsonNode input;
        private JTextField filtersBar;
        
        public JQTab(IMessageEditorController controller, boolean editable) {
            /*
             *  JPanel container
             *    BorderLayout
             *      NORTH
             *      | JPanel filters
             *      |   BoxLayout Y_AXIS
             *      |     JTextField filtersBar
             *      |     JPanel filtersHelpers
             *      |       FlowLayout LEFT
             *      |         JCheckBox checkBoxPretty
             *      |         JCheckBox checkBoxRaw
             *      |         JCheckBox checkBoxSort
             *      |         JCheckBox checkBoxUnique
             *      |         JCheckBox checkBoxFilterOutNulls
             *      |         JCheckBox checkBoxKeys
             *      CENTER
             *        ITextEditor outputArea
             */

            // Filters container
            JPanel filters = new JPanel();
            filters.setLayout(new BoxLayout(filters, BoxLayout.Y_AXIS));

            // Filters bar
            filtersBar = new JTextField();
            filtersBar.setBackground(colorWarning);
            filtersBar.setFont(new Font("monospaced", Font.BOLD, 13));
            filtersBar.setForeground(Color.WHITE);
            Action action = new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    applyFilters();
                }
            };
            filtersBar.addActionListener(action);
            filtersBar.setFocusTraversalKeysEnabled(false);

            // Completion
            jqComplete = new JqComplete(filtersBar);
            filtersBar.getDocument().addDocumentListener(jqComplete);
            filtersBar.getInputMap().put(KeyStroke.getKeyStroke("TAB"), COMMIT_ACTION);
            filtersBar.getActionMap().put(COMMIT_ACTION, jqComplete.new CommitAction());

            // Filters helpers
            JPanel filtersHelpers = new JPanel(new FlowLayout(FlowLayout.LEFT));
            ItemListener helperListener = new ItemListener() {
                public void itemStateChanged(ItemEvent e) {
                    applyFilters();
                }
            };
            checkBoxPretty = new JCheckBox("Pretty", true);
            checkBoxPretty.setToolTipText("Prettify output");
            checkBoxPretty.addItemListener(helperListener);
            checkBoxRaw = new JCheckBox("Raw", true);
            checkBoxRaw.setToolTipText("Trim quotes and render special chars for strings output");
            checkBoxRaw.addItemListener(helperListener);
            checkBoxSort = new JCheckBox("Sort");
            checkBoxSort.setToolTipText("[ <filters> ] | sort | .[]");
            checkBoxSort.addItemListener(helperListener);
            checkBoxUnique = new JCheckBox("Unique");
            checkBoxUnique.setToolTipText("[ <filters> ] | unique | .[]");
            checkBoxUnique.addItemListener(helperListener);
            checkBoxFilterOutNulls = new JCheckBox("Filter out nulls");
            checkBoxFilterOutNulls.setToolTipText("<filters> | select(. != null)");
            checkBoxFilterOutNulls.addItemListener(helperListener);
            checkBoxKeys = new JCheckBox("Keys");
            checkBoxKeys.setToolTipText("<filters> | keys");
            checkBoxKeys.addItemListener(helperListener);

            // Assemble filters helpers
            filtersHelpers.add(checkBoxPretty);
            filtersHelpers.add(checkBoxRaw);
            filtersHelpers.add(checkBoxSort);
            filtersHelpers.add(checkBoxUnique);
            filtersHelpers.add(checkBoxFilterOutNulls);
            filtersHelpers.add(checkBoxKeys);

            // Assemble filters container
            filters.add(filtersBar);
            filters.add(filtersHelpers);

            // Output area
            outputArea = callbacks.createTextEditor();
            outputArea.setEditable(false);

            // Assemble main container
            container.add(filters, BorderLayout.NORTH);
            container.add(outputArea.getComponent(), BorderLayout.CENTER);
        }

        @Override
        public String getTabCaption() {
            return "JQ";
        }

        @Override
        public Component getUiComponent() {
            return container;
        }

        // Enable for JSON only
        @Override
        public boolean isEnabled(byte[] content, boolean isRequest) {

            IRequestInfo requestInfo;
            IResponseInfo responseInfo;

            if (isRequest) {
                requestInfo = helpers.analyzeRequest(content);
                return requestInfo.getContentType() == IRequestInfo.CONTENT_TYPE_JSON;

            } else {
                responseInfo = helpers.analyzeResponse(content);
                return responseInfo.getStatedMimeType().equals("JSON") || responseInfo.getInferredMimeType().equals("JSON");
            }
        }

        @Override
        public void setMessage(byte[] content, boolean isRequest) {

            // Clear our display and reset input
            if (content == null) {
                outputArea.setText(new byte[0]);
                input = null;
                return;
            }

            // Get JSON from request/response body
            int bodyOffset = 0;
            if (isRequest) {
                IRequestInfo requestInfo = helpers.analyzeRequest(content);
                bodyOffset = requestInfo.getBodyOffset();

            } else {
                IResponseInfo responseInfo = helpers.analyzeResponse(content);
                bodyOffset = responseInfo.getBodyOffset();
            }

            // Save JSON input
            try {
                input = mapper.readTree(new String(Arrays.copyOfRange(content, bodyOffset, content.length)));
            } catch (JsonProcessingException e) {
                filtersBar.setBackground(colorError);
                outputArea.setText(e.getMessage().getBytes());
                return;
            }

            // Enumerate keys for completion
            jqComplete.setKeywords(getKeys(input));

            // Draw
            applyFilters();
        }

        // Iterate JsonNode keys
        private HashSet<String> getKeys(JsonNode node) {

            HashSet<String> keys = new HashSet<String>();

            // Arrays
            if (node.isArray()) {
                Iterator<JsonNode> elements = node.iterator();
                while (elements.hasNext()) {
                    getKeys(elements.next()).forEach(key -> {
                        keys.add(key);                  
                    });
                }
            // Other objects
            } else {
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();

                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    keys.add(entry.getKey());
                    getKeys(entry.getValue()).forEach(key -> {
                        keys.add(key);
                    });
                }
            }

            return keys;
        }

        // Apply filters from filterBar and filtersHelpers to JsonNode input and draw output to outputArea
        public void applyFilters() {

            // Helpers
            String filters = filtersBar.getText().trim();
            if (filters.equals(""))
                filters = ".";
            if (checkBoxKeys.isSelected())
                filters += "|keys";
            if (checkBoxFilterOutNulls.isSelected())
                filters += "|select(.!=null)";
            if (checkBoxSort.isSelected() && !checkBoxUnique.isSelected())
                filters = "[" + filters + "]|sort|.[]";
            if (checkBoxUnique.isSelected())
                filters = "[" + filters + "]|unique|.[]";

            try {
                // JQ request and build output with formatting
                StringBuilder output = new StringBuilder();
                JsonQuery jq = JsonQuery.compile(filters, jqVersion);
                AtomicBoolean isAllNulls = new AtomicBoolean(true);
                jq.apply(scope, input, (out) -> {
                    if (!out.isNull())
                        isAllNulls.set(false);
                    if (out.isTextual() && checkBoxRaw.isSelected())
                        output.append(out.asText());
                    else if (checkBoxPretty.isSelected())
                        try {
                            output.append(mapper.writeValueAsString(out));
                        } catch (JsonProcessingException e) {
                            output.append(out.toPrettyString());
                        }
                    else
                        output.append(out.toString());
                    output.append("\n");
                });

                // Remove extra new line
                if (output.length() > 0)
                    output.deleteCharAt(output.length() -1);

                // Draw
                if (isAllNulls.get())
                    filtersBar.setBackground(colorWarning);
                else
                    filtersBar.setBackground(colorOK);
                outputArea.setText(output.toString().getBytes());

            } catch (JsonProcessingException e) {
                filtersBar.setBackground(colorError);
                outputArea.setText(e.getMessage().getBytes());
            }
        }

        @Override
        public byte[] getMessage() {
            return input.toString().getBytes();
        }

        @Override
        public boolean isModified() {
            return false;
        }

        @Override
        public byte[] getSelectedData() {
            return outputArea.getSelectedText();
        }
    }

    @Override
    public IMessageEditorTab createNewInstance(IMessageEditorController controller, boolean editable) {
        return new JQTab(controller, editable);
    }
}
