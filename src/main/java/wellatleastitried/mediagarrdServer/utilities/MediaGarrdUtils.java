package wellatleastitried.mediagarrdServer.utilities;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;

public class MediaGarrdUtils {

    private MediaGarrdUtils() {}

    public static String recordCurrentTime() {
        Instant instant = Instant.now();
        return formatTime(instant);
    }

    public static String formatTime(Instant instant) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String formattedTime = sdf.format(Date.from(instant));
        return formattedTime;
    }

    public static Instant parseTime(String time) throws ParseException {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(time).toInstant();
    }

    public static long getDurationMs(String startTimeStr, String endTimeStr) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            Date startDate = sdf.parse(startTimeStr);
            Date endDate = sdf.parse(endTimeStr);
            return endDate.getTime() - startDate.getTime();
        } catch (ParseException e) {}
        return -1;
    }
}

