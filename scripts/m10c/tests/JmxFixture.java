import java.lang.management.ManagementFactory;
import java.util.*;
import javax.management.*;

/** An idle JDK-only test JVM; no HTTP server, application, database, or workload. */
public class JmxFixture implements DynamicMBean {
    private final Map<String, Object> values;
    JmxFixture(int port) {
        values = Map.of("localPort", port, "connectionCount", 17L, "maxConnections", 8192,
            "keepAliveCount", 13, "currentThreadCount", 10, "currentThreadsBusy", 2,
            "maxThreads", 200, "name", "http-nio-" + port,
            "modelerType", "fixture with \"quotes\" and\nnewline");
    }
    public Object getAttribute(String name) throws AttributeNotFoundException {
        if (!values.containsKey(name)) throw new AttributeNotFoundException(name);
        return values.get(name);
    }
    public AttributeList getAttributes(String[] names) {
        var result = new AttributeList();
        for (String name : names) if (values.containsKey(name)) result.add(new Attribute(name, values.get(name)));
        return result;
    }
    public void setAttribute(Attribute attribute) { throw new UnsupportedOperationException(); }
    public AttributeList setAttributes(AttributeList attributes) { throw new UnsupportedOperationException(); }
    public Object invoke(String action, Object[] args, String[] signature) { throw new UnsupportedOperationException(); }
    public MBeanInfo getMBeanInfo() {
        var attributes = values.entrySet().stream().map(e -> new MBeanAttributeInfo(
            e.getKey(), e.getValue().getClass().getName(), "fixture", true, false, false))
            .toArray(MBeanAttributeInfo[]::new);
        return new MBeanInfo(getClass().getName(), "fixture", attributes, null, null, null);
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || !args[0].equals("empty")) {
            for (int port : List.of(9090, 8080)) {
                ManagementFactory.getPlatformMBeanServer().registerMBean(new JmxFixture(port),
                    new ObjectName("UnusualDomain:type=ThreadPool,name=" + ObjectName.quote("http-nio-" + port)));
            }
        }
        System.out.println("ready");
        System.out.flush();
        System.in.read();
    }
}
