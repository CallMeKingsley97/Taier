package com.dtstack.rdos.common.util;

import com.dtstack.rdos.commom.exception.RdosException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.math3.util.Pair;
import org.dom4j.DocumentException;
import org.jsoup.Jsoup;
import org.jsoup.select.Elements;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * yarn api application 返回的xml解析
 * Date: 2019/7/24
 * Company: www.dtstack.com
 *
 * @author xuchao
 */

public class ApplicationWSParser {

    private static final String AM_ROOT_TAG = "app";
    private static final String AM_CONTAINER_LOGS_TAG = "amContainerLogs";

    private static final Pattern ERR_INFO_BYTE_PATTERN = Pattern.compile("(\\d+)\\s*bytes");

    public static String getAMContainerLogsURL(String jsonStr) throws DocumentException {
        JsonObject jsonObject = (JsonObject) new JsonParser().parse(jsonStr);
        JsonObject roogEle = jsonObject.getAsJsonObject(AM_ROOT_TAG);
        JsonElement amContainerLogsEle = roogEle.get(AM_CONTAINER_LOGS_TAG);
        return amContainerLogsEle.getAsString();
    }

    public static Pair<String, String> parserAMContainerPreViewHttp(String httpText){
        org.jsoup.nodes.Document document = Jsoup.parse(httpText);
        Elements el = document.getElementsByClass("content");
        if(el.size() < 1){
            throw new RdosException("httpText don't have any ele in http class : content");
        }

        Elements afs = el.get(0).select("a[href]");
        String amErrURL = afs.first().attr("href");
        //截取url参数部分
        amErrURL = amErrURL.substring(0, amErrURL.indexOf("?"));

        String jobErrByteStr = afs.first().text();

        String infoTotalBytes = "0";
        Matcher matcher = ERR_INFO_BYTE_PATTERN.matcher(jobErrByteStr);
        if(matcher.find()){
            infoTotalBytes = matcher.group(1);
        }

        return new Pair<>(amErrURL, infoTotalBytes);
    }
}
