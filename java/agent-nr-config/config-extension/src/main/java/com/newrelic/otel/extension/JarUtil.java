package com.newrelic.otel.extension;

import static com.newrelic.otel.extension.JarAnalyzer.JAR_ANALYZER_LOGGER;
import static com.newrelic.otel.extension.JarAnalyzer.JAR_EXTENSION;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.logging.Level;

final class JarUtil {

  private static final AttributeKey<String> SHA_1 = AttributeKey.stringKey("jvm.jar.sha_1");
  private static final AttributeKey<String> SHA_512 = AttributeKey.stringKey("jvm.jar.sha_512");
  private static final AttributeKey<String> JAR_NAME = AttributeKey.stringKey("jvm.jar.name");
  private static final AttributeKey<String> IMPLEMENTATION_VENDOR =
      AttributeKey.stringKey("jvm.jar.implementation_vendor");

  static Attributes toJarAttributes(URL url) {
    AttributesBuilder builder = Attributes.builder();

    try {
      builder.put(SHA_1, computeSha1(url));
    } catch (IOException e) {
      JAR_ANALYZER_LOGGER.log(Level.WARNING, "Error computing SHA-1 for url: " + url, e);
    }

    try {
      builder.put(JAR_NAME, jarName(url));
    } catch (Exception e) {
      JAR_ANALYZER_LOGGER.log(Level.WARNING, "Error determining jar name for url: " + url, e);
    }

    try {
      builder.put(SHA_512, computeSha512(url));
    } catch (IOException e) {
      JAR_ANALYZER_LOGGER.log(Level.WARNING, "Error computing SHA-512 for url: " + url, e);
    }

    try {
      addManifestAttributes(builder, url);
    } catch (IOException e) {
      JAR_ANALYZER_LOGGER.log(Level.WARNING, "Error adding manifest attributes for url: " + url, e);
    }

    try {
      addPomAttributes(builder, url);
    } catch (IOException e) {
      JAR_ANALYZER_LOGGER.log(Level.WARNING, "Error adding pom attributes for url: " + url, e);
    }

    return builder.build();
  }

  private static String computeSha1(URL url) throws IOException {
    return computeSha(url, "SHA1");
  }

  private static String computeSha512(URL url) throws IOException {
    return computeSha(url, "SHA-512");
  }

  private static String computeSha(URL url, String algorithm) throws IOException {
    MessageDigest md;
    try {
      md = MessageDigest.getInstance(algorithm);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(
          "Unexpected error. Checksum algorithm " + algorithm + " does not exist.", e);
    }

    try (InputStream is = new DigestInputStream(url.openStream(), md)) {
      byte[] buffer = new byte[1024 * 8];
      // read in the stream in chunks while updating the digest
      while (is.read(buffer) != -1) {}

      byte[] mdbytes = md.digest();

      // convert to hex format
      StringBuilder sb = new StringBuilder(40);
      for (byte mdbyte : mdbytes) {
        sb.append(Integer.toString((mdbyte & 0xff) + 0x100, 16).substring(1));
      }

      return sb.toString();
    }
  }

  private static String jarName(URL url) throws Exception {
    String path = url.getFile();
    int end = path.lastIndexOf(JAR_EXTENSION);
    if (end > 0) {
      path = path.substring(0, end);
      int start = path.lastIndexOf(File.separator);
      if (start > -1) {
        return path.substring(start + 1) + JAR_EXTENSION;
      }
      return path + JAR_EXTENSION;
    }
    throw new Exception("Cannot extract jar name from url: " + url);
  }

  private static void addManifestAttributes(AttributesBuilder builder, URL url) throws IOException {
    try (InputStream inputStream = url.openStream();
        JarInputStream jarInputStream = new JarInputStream(inputStream)) {
      Manifest manifest = jarInputStream.getManifest();
      if (manifest == null) {
        return;
      }

      java.util.jar.Attributes mainAttributes = manifest.getMainAttributes();
      builder.put(
          IMPLEMENTATION_VENDOR,
          mainAttributes.getValue(java.util.jar.Attributes.Name.IMPLEMENTATION_VENDOR));
    }
  }

  private static void addPomAttributes(AttributesBuilder builder, URL url) throws IOException {
    Properties pom = null;
    try (InputStream inputStream = url.openStream();
        JarInputStream jarInputStream = new JarInputStream(inputStream)) {
      // Advance the jarInputStream to the pom.properties entry
      for (JarEntry entry = jarInputStream.getNextJarEntry();
          entry != null;
          entry = jarInputStream.getNextJarEntry()) {
        if (entry.getName().startsWith("META-INF/maven")
            && entry.getName().endsWith("pom.properties")) {
          if (pom != null) {
            // we've found multiple pom files. bail!
            return;
          }
          pom = new Properties();
          pom.load(jarInputStream);
        }
      }
    }
    if (pom == null) {
      return;
    }
    for (Object key : pom.keySet()) {
      String strKey = (String) key;
      builder.put("jvm.jar.pom." + strKey, pom.getProperty(strKey));
    }
  }

  private JarUtil() {}
}
