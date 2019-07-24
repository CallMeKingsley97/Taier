package com.dtstack.yarn.util;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * @company: www.dtstack.com
 * @Author ：Nanqi
 * @Date ：Created in 10:32 2019-07-24
 * @Description：Gip 压缩工具
 */
public class GZipUtil {

    private static final Logger LOG = LoggerFactory.getLogger(GZipUtil.class);

    private static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8");


    public static byte[] compress(byte[] data) {
        ByteArrayOutputStream bos = null;
        GZIPOutputStream gzip = null;
        try {
            bos = new ByteArrayOutputStream(data.length);
            gzip = new GZIPOutputStream(bos);
            gzip.write(data);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (null != bos) {
                try {
                    bos.close();
                } catch (IOException e) {
                    LOG.error(e.getMessage(), e.getStackTrace());
                }
            }

            if (null != gzip) {
                try {
                    gzip.close();
                } catch (IOException e) {
                    LOG.error(e.getMessage(), e.getStackTrace());
                }
            }
        }
        byte[] compressed = bos.toByteArray();
        return compressed;
    }

    public static byte[] deCompress(byte[] compressed) {
        ByteArrayInputStream bis = new ByteArrayInputStream(compressed);
        GZIPInputStream gis = null;
        byte[] backData = null;
        try {
            gis = new GZIPInputStream(bis);
            backData = IOUtils.toByteArray(gis);
        } catch (IOException e) {
            LOG.error(e.getMessage(), e.getStackTrace());
        } finally {
            if (null != bis) {
                try {
                    bis.close();
                } catch (IOException e) {
                    LOG.error(e.getMessage(), e.getStackTrace());
                }
            }

            if (null != gis) {
                try {
                    gis.close();
                } catch (IOException e) {
                    LOG.error(e.getMessage(), e.getStackTrace());
                }
            }
        }
        return backData;
    }

    public static String compress(String rowData) {
        return Base64Util.baseEncode(new String(compress(rowData.getBytes(StandardCharsets.UTF_8)), DEFAULT_CHARSET));
    }

    public static String deCompress(String rowData) {
        return new String(deCompress(Base64Util.baseDecode(rowData).getBytes(DEFAULT_CHARSET)), StandardCharsets.UTF_8);
    }
}
