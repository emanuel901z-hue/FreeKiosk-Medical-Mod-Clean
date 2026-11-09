/**
 * BatteryOptimization.java
 * 
 * Aktiviert ZUI Battery-Optimierung für Lenovo Tablets (TB336FU):
 * - setBatteryProtectedStatus(true)  → "Schutz beim Aufladen" (40-60% Range)
 * - setBatteryMaintenanceV1Status(true) → "Ladungsoptimierung" (Maintenance V1)
 * - setStopChargingLevel(80) + setStopChargingStatus(true) → "Maximale Ladegrenze 80%"
 * 
 * Nutzung auf dem Tablet:
 *   adb push BatteryOptimization.dex /data/local/tmp/
 *   adb shell CLASSPATH=/data/local/tmp/BatteryOptimization.dex app_process / BatteryOptimization [on|off|status]
 * 
 * Erfordert keine speziellen Berechtigungen — der ZuiBatteryManager Service
 * ist über Reflection für alle Apps erreichbar, die den Service binder finden.
 */
import java.lang.reflect.Method;

public class BatteryOptimization {
    public static void main(String[] args) throws Exception {
        Class<?> zbmClass = Class.forName("android.hardware.battery.ZuiBatteryManager");
        Method getService = zbmClass.getMethod("getService");
        Object service = getService.invoke(null);
        
        if (service == null) {
            System.out.println("ERROR: ZuiBatteryManager.getService() returned null");
            System.exit(1);
        }
        
        String cmd = args.length > 0 ? args[0] : "status";
        
        switch (cmd) {
            case "on":
                System.out.println("=== Aktiviere Battery-Optimierung ===");
                callSetter(service, "setBatteryProtectedStatus", true);
                callSetter(service, "setBatteryMaintenanceV1Status", true);
                callSetter(service, "setAllowStopChargingStatus", true);
                callSetter(service, "setStopChargingLevel", 80);
                callSetter(service, "setStopChargingStatus", true);
                System.out.println();
                printStatus(service);
                break;
                
            case "off":
                System.out.println("=== Deaktiviere Battery-Optimierung ===");
                callSetter(service, "setBatteryProtectedStatus", false);
                callSetter(service, "setBatteryMaintenanceV1Status", false);
                callSetter(service, "setStopChargingStatus", false);
                System.out.println();
                printStatus(service);
                break;
                
            case "status":
                printStatus(service);
                break;
                
            default:
                System.out.println("Usage: BatteryOptimization [on|off|status]");
                System.exit(1);
        }
    }
    
    static void printStatus(Object service) {
        System.out.println("=== ZUI Battery Status ===");
        callGetter(service, "getBatteryProtectedStatus", "Schutz beim Aufladen (40-60%)");
        callGetter(service, "getBatteryMaintenanceV1Status", "Ladungsoptimierung (Maintenance V1)");
        callGetter(service, "getBatteryMaintenanceV2Status", "Maintenance V2");
        callGetter(service, "getStopChargingStatus", "Max. Ladegrenze aktiv");
        callGetter(service, "getStopChargingLevel", "Max. Ladegrenze (%)");
        callGetter(service, "getAllowStopChargingStatus", "StopCharging erlaubt");
        callGetter(service, "isSupportBatteryProtectedFeature", "Protected Feature unterstützt");
        callGetter(service, "isSupportBatteryMaintenanceFeature", "Maintenance Feature unterstützt");
        callGetter(service, "isSupportECOBatteryStopChargingFeature", "StopCharging Feature unterstützt");
    }
    
    static void callGetter(Object service, String methodName, String label) {
        try {
            Method m = service.getClass().getMethod(methodName);
            Object result = m.invoke(service);
            System.out.println("  " + label + ": " + result);
        } catch (Exception e) {
            System.out.println("  " + label + ": ERROR (" + e.getMessage() + ")");
        }
    }
    
    static void callSetter(Object service, String methodName, Object value) {
        try {
            Class<?> paramType = value instanceof Boolean ? boolean.class : (value instanceof Integer ? int.class : value.getClass());
            Method m = service.getClass().getMethod(methodName, paramType);
            m.invoke(service, value);
            System.out.println("  " + methodName + "(" + value + ") → OK");
        } catch (Exception e) {
            System.out.println("  " + methodName + "(" + value + ") → ERROR: " + e.getMessage());
        }
    }
}
