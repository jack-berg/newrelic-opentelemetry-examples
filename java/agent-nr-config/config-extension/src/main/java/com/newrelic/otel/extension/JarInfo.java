package com.newrelic.otel.extension;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.jar.*;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;

public class JarInfo {

    private static final Map<String,String> EMBEDDED_FORMAT_TO_EXTENSION = Stream.of("ear","war","jar").collect(toMap(ext -> '.' + ext + "!/", ext -> ext));

    private String version;
    private Map<String, String> attributes;

    private JarInfo(String version, Map<String, String> attributes) {
        this.version = version;
        this.attributes = attributes;
    }

    static JarInfo create(URL url) throws Exception {
        Map<String, String> attributes = new HashMap<>();
        // Compute checksum attributes
        try {
            attributes.put("sha1Checksum", computeSha(url, "SHA1"));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        try {
            attributes.put("sha512Checksum", computeSha(url, "SHA-512"));
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        try (JarInputStream jarFile = urlToJarInputStream(url)) {
            Manifest manifest = jarFile.getManifest();
            if (manifest != null) {
                Attributes jarAttributes = manifest.getMainAttributes();
                for (String name : Arrays.asList(Attributes.Name.IMPLEMENTATION_VENDOR.toString(), Attributes.Name.IMPLEMENTATION_VENDOR_ID.toString())) {
                    String value = jarAttributes.getValue(name);
                    if (null != value) {
                        attributes.put(name, value);
                    }
                }
            }

            Map<String, String> pom = null;

            for (JarEntry entry = jarFile.getNextJarEntry(); entry != null; entry = jarFile.getNextJarEntry()) {
                if (entry.getName().startsWith("META-INF/maven") && entry.getName().endsWith("pom.properties")) {
                    if (pom != null) {
                        // we've found multiple pom files. bail!
                        return null;
                    }
                    Properties props = new Properties();
                    props.load(jarFile);

                    pom = (Map) props;
                }
            }
            if (pom != null) {
                attributes.putAll(pom);
                return new JarInfo(pom.get("version"), attributes);
            }
        }
        return new JarInfo("UNKNOWN", attributes);
    }

    private static String computeSha(URL url, String algorithm) throws NoSuchAlgorithmException, IOException {
        InputStream inputStream = urlToInputStream(url);

        try {
            final MessageDigest md = MessageDigest.getInstance(algorithm);

            DigestInputStream dis = new DigestInputStream(inputStream, md);
            byte[] buffer = new byte[1024 * 8];
            // read in the stream in chunks while updating the digest
            while (dis.read(buffer) != -1) {
            }

            byte[] mdbytes = md.digest();

            // convert to hex format
            StringBuffer sb = new StringBuffer(40);
            for (int i = 0; i < mdbytes.length; i++) {
                sb.append(Integer.toString((mdbytes[i] & 0xff) + 0x100, 16).substring(1));
            }

            return sb.toString();
        } finally {
            inputStream.close();
        }
    }

    private static InputStream urlToInputStream(URL url) throws IOException {
        for (Map.Entry<String, String> entry : EMBEDDED_FORMAT_TO_EXTENSION.entrySet()) {
            int index = url.toExternalForm().indexOf(entry.getKey());
            if (index > 0) {

                String path = url.toExternalForm().substring(index + entry.getKey().length());
                // add 1 to skip past the `.` and the value length, which is the length of the file extension
                url = new URL(url.toExternalForm().substring(0, index + 1 + entry.getValue().length()));
                // For some reason, some JAR files cannot be read properly by JarInputStream, at least the getNextJarEntry method
                // perhaps related to entry order (https://bugs.openjdk.org/browse/JDK-8031748)
                JarFile jarFile = new JarFile(url.getFile());
                JarEntry innerEntry = jarFile.getJarEntry(path);
                return jarFile.getInputStream(innerEntry);
            }
        }
        return url.openStream();
    }

    private static JarInputStream urlToJarInputStream(URL url) throws IOException {
        boolean isEmbedded = isEmbedded(url);
        InputStream stream = urlToInputStream(url);
        if (!isEmbedded && stream instanceof JarInputStream) {
            return (JarInputStream) stream;
        }
        return new JarInputStream(stream);
    }

    private static boolean isEmbedded(URL url) {
        String externalForm = url.toExternalForm();
        for (String prefix : EMBEDDED_FORMAT_TO_EXTENSION.keySet()) {
            if (externalForm.contains(prefix)) {
                return true;
            }
        }
        return false;
    }

    public String getVersion() {
        return version;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    @Override
    public String toString() {
        return "JarInfo{version=" + version + ", attributes=" + attributes.toString() + "}";
    }
}
