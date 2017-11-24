package com.dtstack.rdos.engine.execution.base.util;

import com.dtstack.rdos.common.util.MathUtil;
import com.dtstack.rdos.engine.execution.base.pojo.SparkJobLog;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.tuple.Pair;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reason:
 * Date: 2017/11/23
 * Company: www.dtstack.com
 * @ahthor xuchao
 */

public class SparkStandaloneRestParseUtil {

    private static final Logger logger = LoggerFactory.getLogger(SparkStandaloneRestParseUtil.class);

    public final static String ROOT = "/";

    public static final String DRIVER_LOGURL_FORMAT = "%s/logPage/?driverId=%s&logType=stderr";

    public static final String APP_LOGURL_FORMAT = "/app/?appId=%s";

    public final static String ADDRESS_KEY = "address";

    public final static String CORE_TOTAL_KEY = "cores.total";

    public final static String CORE_USED_KEY = "cores.used";

    public final static String CORE_FREE_KEY = "cores.free";

    public final static String MEMORY_TOTAL_KEY = "memory.total";

    public final static String MEMORY_USED_KEY = "memory.used";

    public final static String MEMORY_FREE_KEY = "memory.free";

    public final static String SPARK_ENGINE_DOWN = "Current state is not alive: STANDBY";

    private static Pattern memPattern = Pattern.compile("\\s*(\\d+\\.?\\d*)\\s*([G|K|M]?B)\\s*\\(([\\-]?\\d+\\.?\\d*)\\s*([G|K|M]?B)\\s+Used\\)");

    private static Pattern corePattern = Pattern.compile("\\s*(\\d+)\\s*\\(([\\-]?\\d+)\\s+Used\\)");

    private static int MB2B = 1024 * 1024;

    /**
     * message 为 html字符串
     * @param message
     * @return
     */
    public static Map<String, Map<String, Object>> getAvailSlots(String message){

        Map<String, Map<String, Object>> availSlotMap = new HashMap<>();
        Document doc = Jsoup.parse(message);
        Elements rootEles = doc.getElementsMatchingOwnText("Worker Id");
        Elements workChildEles = rootEles.first().parent().parent().parent().child(1).children();
        for (int i = 0; i < workChildEles.size(); i++) {
            Element element = workChildEles.get(i);
            String workId = element.child(0).text();
            String address = element.child(1).text();
            String state = element.child(2).text();
            String cores = element.child(3).text();
            String memory = element.child(4).text();

            //不统计dead节点信息
            if(!state.equalsIgnoreCase("alive")){
                continue;
            }

            Pair<Integer, Integer> coreInfo = parserCore(cores);
            Pair<Integer, Integer> memInfo = parserMemory(memory);

            if(coreInfo == null){
                logger.error("parser worker's core info error {}.", cores);
                continue;
            }

            if(memInfo == null){
                logger.error("parser worker's core info error {}.", memInfo);
                continue;
            }

            //spark页面上的统计数据本身有问题
            if(coreInfo.getRight() < 0 || memInfo.getRight() < 0){
                continue;
            }

            Map<String, Object> workerInfo = Maps.newHashMap();
            workerInfo.put(ADDRESS_KEY, address);
            workerInfo.put(CORE_TOTAL_KEY, coreInfo.getLeft());
            workerInfo.put(CORE_USED_KEY, coreInfo.getRight());
            workerInfo.put(CORE_FREE_KEY, coreInfo.getLeft() - coreInfo.getRight());

            workerInfo.put(MEMORY_TOTAL_KEY, memInfo.getLeft());
            workerInfo.put(MEMORY_USED_KEY, memInfo.getRight());
            workerInfo.put(MEMORY_FREE_KEY, memInfo.getLeft() - memInfo.getRight());

            availSlotMap.put(workId, workerInfo);
        }

        return availSlotMap;
    }

    /**
     * eg:4 (0 Used)
     * 可能的情况为:8 (-1 Used)
     * @return
     */
    public static Pair<Integer, Integer> parserCore(String coresStr){
        Matcher matcher = corePattern.matcher(coresStr);
        if(matcher.find() && matcher.groupCount() == 2){
            String totalStr = matcher.group(1);
            String usedStr = matcher.group(2);
            Integer total = MathUtil.getIntegerVal(totalStr);
            Integer used = MathUtil.getIntegerVal(usedStr);
            return Pair.of(total, used);
        }

        return null;
    }

    /**
     * eg: 6.8 GB (0.0 B Used)
     * 可能的情况为: 14.0 GB (-1073741824.0 B Used)
     * @param memStr
     * @return
     */
    public static Pair<Integer, Integer> parserMemory(String memStr){
        Matcher matcher = memPattern.matcher(memStr);
        if(matcher.find() && matcher.groupCount() == 4){
            String total = matcher.group(1);
            String totalUnit = matcher.group(2);

            String used = matcher.group(3);
            String usedUnit = matcher.group(4);

            int totalResult = convert2MB(total, totalUnit);
            int usedResult = convert2MB(used, usedUnit);
            return Pair.of(totalResult, usedResult);
        }

        return null;
    }

    public static int convert2MB(String capacityStr, String unit){
        if(unit.equalsIgnoreCase("GB")){
            return GB2MB(capacityStr);
        }else if(unit.equalsIgnoreCase("KB")){
            return KB2MB(capacityStr);
        }else if(unit.equalsIgnoreCase("B")){
            return B2MB(capacityStr);
        }else if(unit.equalsIgnoreCase("MB")){
            return Double.valueOf(capacityStr).intValue();
        }else{
            throw new RuntimeException("not support unit:" + unit);
        }
    }

    public static int GB2MB(String gbStr){
        Double gbVal = Double.valueOf(gbStr);
        Double mbVal = gbVal * 1024;
        return mbVal.intValue();
    }

    public static int KB2MB(String kbStr){
        Double kbVal = Double.valueOf(kbStr);
        Double mbVal = kbVal/1024;
        return mbVal.intValue();
    }

    public static int B2MB(String bStr){
        Double bVal = Double.valueOf(bStr);
        Double mbVal = bVal/MB2B;
        return mbVal.intValue();
    }


    public static String getDriverLog(String message, String engineJobId){

        Document rootDoc = Jsoup.parse(message);
        Element workerEle = rootDoc.getElementsContainingOwnText(engineJobId)
                .first().parent().child(2).select("a").first();
        String workerUrl = workerEle.attr("href");
        return getLog(String.format(DRIVER_LOGURL_FORMAT, workerUrl, engineJobId));
    }

    public static String getAppId(String driverLog){

        String appId = null;

        try{
            BufferedReader reader = new BufferedReader(new StringReader(driverLog));
            String line = null;
            while ((line = reader.readLine()) != null) {
                if (line.contains("Connected to Spark cluster with app ID ")) {
                    appId = line.split("Connected to Spark cluster with app ID ")[1];
                    break;
                }
            }
            return appId;
        } catch (Exception e) {
            logger.info("", e);
        }

        return null;
    }

    public static SparkJobLog getAppLog(String appMessage) {

        SparkJobLog sparkJobLog = new SparkJobLog();
        Document doc = Jsoup.parse(appMessage);
        Elements appLogEles = doc.getElementsContainingOwnText("stderr");

        for (int i = 0; i < appLogEles.size(); i++) {
            String appLogUrl = appLogEles.get(i).attr("href");
            String appLog = getLog(appLogUrl);
            String workerId = appLogEles.get(i).parent().parent().child(1).text();
            sparkJobLog.addAppLog(workerId, appLog);
        }

        return sparkJobLog;
    }

    private static String getLog(String url) {
        String log = null;
        try {
            Document doc = Jsoup.connect(url).get();
            log = doc.select("pre").text();
        } catch (IOException e) {
            logger.info("" ,e);
        }
        return log;
    }

    public static boolean checkFailureForEngineDown(String msg){
        if(msg.contains(SPARK_ENGINE_DOWN)){
            return true;
        }

        return false;
    }
}
