package com.qoder.payment.common.secret;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 本地文件密钥实现（local profile 默认，零依赖）：读 {@code ~/.config/payment-pro/keys/}。
 * <ul>
 *   <li>对称密钥：{@code <ref>.key}（文本）。缺失时惰性生成随机密钥并落盘（600），便于 local 开箱即用。</li>
 *   <li>RSA 私钥：{@code <ref>.pk8}（PKCS#8 DER）或 {@code <ref>-private.pem}（PEM）。</li>
 *   <li>证书：{@code <ref>.crt} / {@code <ref>.pem}（X509）。</li>
 * </ul>
 * 文件权限 600、目录 700（POSIX 系统）；密钥明文绝不写日志。docker 未启用 Vault 时亦回退本实现。
 */
@Slf4j
public class LocalFileSecretProvider implements SecretProvider {

    private static final Pattern REF = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Set<PosixFilePermission> FILE_600 = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> DIR_700 = PosixFilePermissions.fromString("rwx------");

    private final Path baseDir;
    private final SecureRandom random = new SecureRandom();

    public LocalFileSecretProvider() {
        this(Path.of(System.getProperty("user.home"), ".config", "payment-pro", "keys"));
    }

    public LocalFileSecretProvider(Path baseDir) {
        this.baseDir = baseDir;
    }

    @Override
    public String getSecret(String ref) {
        Path file = resolve(ref, ".key");
        try {
            if (Files.exists(file)) {
                return Files.readString(file, StandardCharsets.UTF_8).trim();
            }
            // local 惰性 provisioning：平台共享密钥等首次使用即生成，保证零依赖开箱即用。
            byte[] raw = new byte[16];
            random.nextBytes(raw);
            String secret = HexFormat.of().formatHex(raw);
            ensureDir();
            Files.writeString(file, secret, StandardCharsets.UTF_8);
            restrict(file);
            log.info("本地密钥惰性生成 ref={} path={}", ref, file);
            return secret;
        } catch (IOException e) {
            throw new IllegalStateException("读取本地密钥失败 ref=" + ref, e);
        }
    }

    @Override
    public PrivateKey getPrivateKey(String ref) {
        validate(ref);
        Path der = baseDir.resolve(ref + ".pk8");
        Path pem = baseDir.resolve(ref + "-private.pem");
        try {
            byte[] pkcs8;
            if (Files.exists(der)) {
                pkcs8 = Files.readAllBytes(der);
            } else if (Files.exists(pem)) {
                pkcs8 = decodePem(Files.readString(pem, StandardCharsets.UTF_8));
            } else {
                return null;
            }
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (Exception e) {
            throw new IllegalStateException("读取本地私钥失败 ref=" + ref, e);
        }
    }

    @Override
    public X509Certificate getCert(String ref) {
        validate(ref);
        for (String suffix : new String[]{".crt", ".pem"}) {
            Path file = baseDir.resolve(ref + suffix);
            if (!Files.exists(file)) {
                continue;
            }
            try (InputStream in = Files.newInputStream(file)) {
                return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            } catch (Exception e) {
                throw new IllegalStateException("读取本地证书失败 ref=" + ref, e);
            }
        }
        return null;
    }

    private Path resolve(String ref, String suffix) {
        validate(ref);
        return baseDir.resolve(ref + suffix);
    }

    private static void validate(String ref) {
        if (ref == null || !REF.matcher(ref).matches() || ref.contains("..")) {
            throw new IllegalArgumentException("非法密钥引用: " + ref);
        }
    }

    private void ensureDir() throws IOException {
        if (!Files.exists(baseDir)) {
            Files.createDirectories(baseDir);
            try {
                Files.setPosixFilePermissions(baseDir, DIR_700);
            } catch (UnsupportedOperationException ignored) {
                // 非 POSIX 文件系统（如 Windows）跳过权限收紧。
            }
        }
    }

    private static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file, FILE_600);
        } catch (UnsupportedOperationException | IOException ignored) {
            // 非 POSIX 文件系统跳过；密钥仍不落仓库/日志。
        }
    }

    private static byte[] decodePem(String pem) {
        String base64 = pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}