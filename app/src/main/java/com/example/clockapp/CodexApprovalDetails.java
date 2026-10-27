package com.example.clockapp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Turns approval arguments into readable fields without discarding unfamiliar arguments. */
final class CodexApprovalDetails {
    static final class Field {
        final String label;
        final String value;
        final boolean code;
        Field(String label, String value, boolean code) {
            this.label = label;
            this.value = value;
            this.code = code;
        }
    }

    static List<Field> parse(JSONObject request) {
        List<Field> fields = new ArrayList<>();
        Object input = request.opt("input");
        if (input instanceof String) {
            // Some hook transports serialize their argument object into a string.
            try {
                Object parsed = new JSONTokener((String) input).nextValue();
                if (parsed instanceof JSONObject || parsed instanceof JSONArray) input = parsed;
            } catch (Exception ignored) { }
        }
        append(fields, "", input, false);
        add(fields, "Tool", request.opt("tool"), false);
        add(fields, "Folder", request.opt("cwd"), false);
        add(fields, "Chat ID", request.opt("session"), false);
        return fields;
    }

    private static void append(List<Field> fields, String path, Object value, boolean code) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String[] preferred = {"name", "reason", "justification", "description", "command", "cmd", "commands", "code"};
            for (String key : preferred) {
                if (object.has(key)) appendKey(fields, path, key, object.opt(key), code);
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                boolean handled = false;
                for (String first : preferred) if (first.equals(key)) handled = true;
                if (!handled) appendKey(fields, path, key, object.opt(key), code);
            }
            if (object.length() == 0) add(fields, path, "No arguments", false);
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                append(fields, join(path, "Item " + (i + 1)), array.opt(i), code);
            }
            if (array.length() == 0) add(fields, path, "None", false);
        } else {
            add(fields, path.isEmpty() ? "Action" : path, value, code);
        }
    }

    private static void appendKey(List<Field> fields, String path, String key, Object value, boolean code) {
        boolean command = key.equals("command") || key.equals("cmd") || key.equals("commands") || key.equals("code");
        String label;
        switch (key) {
            case "cmd": case "command": label = "Command"; break;
            case "commands": label = "Commands"; break;
            case "reason": case "justification": case "description": label = "Reason"; break;
            case "code": label = "Script"; break;
            case "cwd": case "workdir": label = "Working folder"; break;
            default:
                label = key.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').replace('-', ' ');
                if (!label.isEmpty()) label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
        }
        append(fields, join(path, label), value, code || command);
    }

    private static String join(String path, String label) {
        return path.isEmpty() ? label : path + " / " + label;
    }

    private static void add(List<Field> fields, String label, Object value, boolean code) {
        if (value == null || value == JSONObject.NULL || value.toString().isEmpty()) return;
        fields.add(new Field(label.isEmpty() ? "Arguments" : label, String.valueOf(value), code));
    }
}
