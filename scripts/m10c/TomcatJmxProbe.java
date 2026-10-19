import com.sun.tools.attach.VirtualMachine;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.management.*;
import javax.management.remote.*;

/** Diagnostic-only, persistent local JMX reader. The Python owner enforces deadlines. */
public class TomcatJmxProbe {
    private static final Set<String> TYPES = Set.of("ThreadPool", "Connector", "ProtocolHandler");
    private static final Set<String> ATTRIBUTES = Set.of(
        "connectionCount", "maxConnections", "keepAliveCount", "currentThreadCount",
        "currentThreadsBusy", "maxThreads", "localPort", "port", "name", "protocol",
        "protocolHandlerClassName", "modelerType");

    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            var parts = new ArrayList<String>();
            map.forEach((k, v) -> parts.add(json(k.toString()) + ":" + json(v)));
            return "{" + String.join(",", parts) + "}";
        }
        if (value instanceof Collection<?> items) {
            return "[" + String.join(",", items.stream().map(TomcatJmxProbe::json).toList()) + "]";
        }
        var out = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    static void emit(Object value) {
        System.out.println(json(value));
        System.out.flush();
    }

    static List<Map<String, Object>> discover(MBeanServerConnection server) throws Exception {
        var result = new ArrayList<Map<String, Object>>();
        // ObjectName handles quoted names. No assumption about the Catalina/Tomcat domain.
        var names = new TreeSet<ObjectName>();
        for (String type : TYPES) names.addAll(server.queryNames(new ObjectName("*:type=" + type + ",*"), null));
        for (ObjectName name : names) {
            var row = new LinkedHashMap<String, Object>();
            row.put("object_name", name.getCanonicalName());
            row.put("type", name.getKeyProperty("type"));
            var keys = new TreeMap<String, String>();
            name.getKeyPropertyList().forEach((k, v) -> keys.put(k,
                v.startsWith("\"") ? ObjectName.unquote(v) : v));
            row.put("keys", keys);
            var values = new TreeMap<String, Object>();
            var errors = new TreeMap<String, String>();
            var available = new TreeSet<String>();
            try {
                for (MBeanAttributeInfo info : server.getMBeanInfo(name).getAttributes()) {
                    if (!info.isReadable()) continue;
                    available.add(info.getName());
                    if (!ATTRIBUTES.contains(info.getName())) continue;
                    try {
                        Object value = server.getAttribute(name, info.getName());
                        values.put(info.getName(), value instanceof Number || value instanceof Boolean
                            || value instanceof String ? value : null);
                    } catch (Exception e) { errors.put(info.getName(), e.toString()); }
                }
            } catch (Exception e) { errors.put("mbean_info", e.toString()); }
            row.put("available_attributes", available);
            row.put("attributes", values);
            row.put("errors", errors);
            result.add(row);
        }
        return result;
    }

    public static void main(String[] args) {
        String stage = "attach";
        try {
            if (Runtime.version().feature() != 21) throw new IllegalStateException("Use Java 21 for this experiment");
            String pid = args[0];
            String expectedMain = args[1];
            String address;
            boolean agentStarted = false;
            VirtualMachine vm = VirtualMachine.attach(pid);
            try {
                // Confirm the actual main before enabling management (guards PID reuse and Maven).
                String command = vm.getSystemProperties().getProperty("sun.java.command", "");
                if (!command.split("\\s+", 2)[0].equals(expectedMain)) {
                    throw new IllegalStateException("Target main does not match " + expectedMain);
                }
                address = vm.getAgentProperties().getProperty("com.sun.management.jmxremote.localConnectorAddress");
                if (address == null) {
                    address = vm.startLocalManagementAgent();
                    agentStarted = true;
                }
            } finally { vm.detach(); }
            stage = "connect";
            try (JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(address));
                 var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                var server = connector.getMBeanServerConnection();
                stage = "sample";
                String line;
                while ((line = input.readLine()) != null) {
                    if (!line.equals("sample")) throw new IllegalArgumentException("Expected sample request");
                    var result = new LinkedHashMap<String, Object>();
                    result.put("ok", true);
                    result.put("pid", Long.parseLong(pid));
                    result.put("sampled_at", Instant.now().toString());
                    result.put("agent_started_by_helper", agentStarted);
                    result.put("mbeans", discover(server));
                    emit(result);
                }
            }
        } catch (Exception e) {
            emit(Map.of("ok", false, "stage", stage, "error", e.toString()));
            System.exit(1);
        }
    }
}
