package nodomain.freeyourgadget.gadgetbridge;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;

/**
 * 把最新的手腕数据写成一个小 JSON 文件，供外部程序读取。
 *
 * 输出路径：
 *   /sdcard/Android/data/nodomain.freeyourgadget.gadgetbridge/files/health.json
 *
 * 设计原则：
 *   - 只做“转存”，不做任何推算
 *   - 任何异常都必须被吞掉，绝不能影响 Gadgetbridge 主流程
 */
public class HealthJsonDumper {

    private static Context sContext;
    private static int sSteps = 0;
    private static int sHeartRate = 0;
    private static int sBattery = -1;
    private static long sLastWrite = 0L;

    /** 手表推送数据时调用：steps 为当前累计步数，heartRate 为当前心率 */
    public static void update(Context context, int steps, int heartRate) {
        if (context != null) {
            sContext = context.getApplicationContext();
        }
        if (steps >= 0) {
            sSteps = steps;
        }
        if (heartRate > 0) {
            sHeartRate = heartRate;
        }
        write();
    }

    public static void setBattery(int level) {
        sBattery = level;
        write();
    }

    private static void write() {
        // 每秒最多写一次，避免频繁 IO
        if (System.currentTimeMillis() - sLastWrite < 1000L) {
            return;
        }
        sLastWrite = System.currentTimeMillis();

        try {
            Context ctx = sContext;
            if (ctx == null) {
                return;
            }
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            if (!dir.exists()) {
                dir.mkdirs();
            }

            long ts = System.currentTimeMillis() / 1000L;
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"steps\": ").append(sSteps).append(",\n");
            sb.append("  \"heart_rate\": ").append(sHeartRate).append(",\n");
            sb.append("  \"battery_level\": ").append(sBattery).append(",\n");
            sb.append("  \"sample_timestamp\": ").append(ts).append(",\n");
            sb.append("  \"connected\": true\n");
            sb.append("}\n");

            File f = new File(dir, "health.json");
            FileWriter w = new FileWriter(f, false);
            w.write(sb.toString());
            w.flush();
            w.close();
        } catch (Throwable ignored) {
            // 写文件失败也不能影响 GB 本身
        }
    }
}
