package demo;

import java.util.ArrayList;
import java.util.List;

public class AuditLogger {

    private final List<String> entries = new ArrayList<>();

    public void record(String action, long orderId) {
        entries.add(action + ":" + orderId);
        if (entries.size() > 1000) {
            entries.clear();
        }
    }

    public List<String> entries() {
        return entries;
    }
}
