package burp;

import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;


//https://stackabuse.com/example-adding-autocomplete-to-jtextfield/
public class JqComplete implements DocumentListener {

    private static enum Mode {
        INSERT,
        COMPLETION
    };
    private static final List<String> mates = Arrays.asList("()", "{}", "[]", "''", "\"\"");
    private static final List<String> keywords = Arrays.asList(
        "add",
        "all",
        "any",
        "arrays",
        "ascii_downcase",
        "ascii_upcase",
        "booleans",
        "bsearch()",
        "combinations",
        "contains()",
        "del()",
        "delpaths()",
        "empty",
        "endswith()",
        "error()",
        "explode",
        "finites",
        "flatten",
        "floor",
        "from_entries",
        "getpath()",
        "group_by()",
        "halt",
        "halt_error",
        "has()",
        "implode",
        "in",
        "index()",
        "indices()",
        "infinite",
        "inside",
        "isfinite",
        "isinfinite",
        "isnormal",
        "iterables",
        "join()",
        "keys",
        "keys_unsorted",
        "leaf_paths",
        "length",
        "ltrimstr()",
        "map()",
        "map_values()",
        "max",
        "max_by()",
        "min",
        "min_by()",
        "nan",
        "normals",
        "nulls",
        "numbers",
        "objects",
        "path()",
        "paths",
        "range()",
        "recurse",
        "recurse_down",
        "reverse",
        "rindex()",
        "rtrimstr()",
        "scalars",
        "select()",
        "select()",
        "setpath()",
        "sort",
        "sort_by()",
        "split()",
        "sqrt",
        "startswith()",
        "strings",
        "to_entries",
        "tonumber",
        "tostring",
        "transpose",
        "unique",
        "unique_by()",
        "until()",
        "utf8bytelength",
        "values",
        "walk()",
        "while()",
        "with_entries");

    private List<String> keys;
    private Mode mode = Mode.INSERT;
    private final JTextField textField;

    public JqComplete(JTextField textField) {
        this.textField = textField;
        ((AbstractDocument) this.textField.getDocument()).setDocumentFilter(new JqDocumentFilter());
        this.keys = new ArrayList<String>();
    }

    public void addKeyword(String keyword) {
        keys.add(keyword);
        Collections.sort(keys);
    }

    public void setKeywords(List<String> keys) {
        this.keys = keys;
        Collections.sort(this.keys);
    }

    public void setKeywords(HashSet<String> keys) {
        this.keys = new ArrayList<String>(keys);
        Collections.sort(this.keys);
    }

    public void clearKeywords() {
        keys.clear();
    }

    class JqDocumentFilter extends DocumentFilter {

        public JqDocumentFilter() { }

        @Override
        public void remove(DocumentFilter.FilterBypass fb, int offset, int length) throws BadLocationException {
            int i;
            for (i = 0; i < mates.size(); i++) {
                if (textField.getText(offset, length + 1).equals(mates.get(i))) {
                    length++;
                    break;
                }
            }
            super.remove(fb, offset, length);
        }
    }

    @Override
    public void changedUpdate(DocumentEvent ev) { }

    @Override
    public void removeUpdate(DocumentEvent ev) { }

    @Override
    public void insertUpdate(DocumentEvent ev) {

        if (ev.getLength() != 1)
            return;
 
        int pos = ev.getOffset();
        String content = null;
        String after = null;
        try {
            content = textField.getText(0, pos + 1);
            after = textField.getText(pos + 1, 1);
        } catch (BadLocationException e) {
            return;
        }

        // Mates completion
        int i;
        for (i = 0; i < mates.size(); i++) {
            // Do not insert already present closing mate
            if (content.charAt(pos) == after.charAt(0) && content.charAt(pos) == mates.get(i).charAt(1)) {
                SwingUtilities.invokeLater(new MatesTask((char) 0, pos + 1));
                return;
            // Insert mates together
            } else if (content.charAt(pos) == mates.get(i).charAt(0)) {
                SwingUtilities.invokeLater(new MatesTask(mates.get(i).charAt(1), pos + 1));
                return;
            }
        }

        // Find where the word starts
        int w;
        for (w = pos; w > 0; w--) {
            if (!Character.isLetter(content.charAt(w)) && content.charAt(w) != '_')
                break;
        }

        // JSON keys or JQ keywords
        List<String> lst = keywords;
        if (content.charAt(w) == '.')
            lst = keys;

        // Handle start of text field
        if (w == 0 && Character.isLetter(content.charAt(w)))
            w--;
        // Too few chars
        else if (pos - w < 1)
            return;

        String prefix = content.substring(w + 1);
        int n = Collections.binarySearch(lst, prefix);
        if (n < 0 && -n <= lst.size()) {
            String match = lst.get(-n - 1);
            // A completion is found
            if (match.startsWith(prefix)) {
                String completion = match.substring(pos - w);
                // We cannot modify Document from within notification,
                // so we submit a task that does the change later
                SwingUtilities.invokeLater(new CompletionTask(completion, pos + 1));
            }
        // Nothing found
        } else {
            mode = Mode.INSERT;
        }
    }

    public class CommitAction extends AbstractAction {

        private static final long serialVersionUID = 5794543109646743416L;

        @Override
        public void actionPerformed(ActionEvent ev) {
            if (mode == Mode.COMPLETION) {
                int pos = textField.getSelectionEnd();
                String completion = textField.getSelectedText();
                StringBuffer sb = new StringBuffer(textField.getText());
                textField.setText(sb.toString());
                if (completion != null && completion.charAt(completion.length() - 1) == ')')
                    textField.setCaretPosition(pos - 1);
                else
                    textField.setCaretPosition(pos);
                mode = Mode.INSERT;
            } else {
                textField.replaceSelection("\t");
            }
        }
    }

    private class CompletionTask implements Runnable {
        private String completion;
        private int position;
 
        CompletionTask(String completion, int position) {
            this.completion = completion;
            this.position = position;
        }
 
        public void run() {
            StringBuffer sb = new StringBuffer(textField.getText());
            sb.insert(position, completion);
            textField.setText(sb.toString());
            textField.setCaretPosition(position + completion.length());
            textField.moveCaretPosition(position);
            mode = Mode.COMPLETION;
        }
    }

    private class MatesTask implements Runnable {
        private char mate;
        private int position;
 
        MatesTask(char mate, int position) {
            this.mate = mate;
            this.position = position;
        }
 
        public void run() {
            StringBuffer sb = new StringBuffer(textField.getText());
            if (mate != 0)
                sb.insert(position, mate);
            else
                sb.deleteCharAt(position);
            textField.setText(sb.toString());
            textField.setCaretPosition(position);
        }
    }
}
