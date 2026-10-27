package com.example.clockapp;

import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class CodexApprovalDetailsTest {
    @Test public void commandAndReasonAreSeparateAndCommandIsUnchanged() throws Exception {
        String command = "printf '%s\\n' '{\"value\":1}'\nls /tmp";
        JSONObject input = new JSONObject().put("cmd", command).put("justification", "Check the build")
                .put("name", "Build check").put("sandbox_permissions", "require_escalated");
        List<CodexApprovalDetails.Field> fields = CodexApprovalDetails.parse(
                new JSONObject().put("input", input).put("tool", "Bash").put("session", "chat-123"));
        assertEquals("Build check", find(fields, "Name").value);
        assertEquals("Check the build", find(fields, "Reason").value);
        assertEquals(command, find(fields, "Command").value);
        assertTrue(find(fields, "Command").code);
        assertEquals("require_escalated", find(fields, "Sandbox permissions").value);
        assertEquals("chat-123", find(fields, "Chat ID").value);
    }

    @Test public void nestedArgumentsAndArraysRemainVisibleWithoutJsonWrappers() throws Exception {
        JSONObject input = new JSONObject("{\"actions\":[{\"command\":\"pwd\",\"description\":\"Inspect location\"}],\"network\":{\"enabled\":true}}");
        List<CodexApprovalDetails.Field> fields = CodexApprovalDetails.parse(new JSONObject().put("input", input));
        assertEquals("pwd", find(fields, "Actions / Item 1 / Command").value);
        assertEquals("Inspect location", find(fields, "Actions / Item 1 / Reason").value);
        assertEquals("true", find(fields, "Network / Enabled").value);
    }

    @Test public void serializedArgumentsAreDecodedAndPlainActionsStillWork() throws Exception {
        List<CodexApprovalDetails.Field> fields = CodexApprovalDetails.parse(
                new JSONObject().put("input", "{\"command\":\"pwd\",\"reason\":\"Check folder\"}"));
        assertEquals("pwd", find(fields, "Command").value);
        assertEquals("Check folder", find(fields, "Reason").value);
        fields = CodexApprovalDetails.parse(new JSONObject().put("input", "Open the document"));
        assertEquals("Open the document", find(fields, "Action").value);
    }

    private CodexApprovalDetails.Field find(List<CodexApprovalDetails.Field> fields, String label) {
        for (CodexApprovalDetails.Field field : fields) if (label.equals(field.label)) return field;
        throw new AssertionError("Missing field: " + label);
    }
}
