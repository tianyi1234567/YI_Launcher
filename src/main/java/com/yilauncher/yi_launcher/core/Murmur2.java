package com.yilauncher.yi_launcher.core;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CurseForge 的文件指纹：MurmurHash2（32 位，seed=1），计算前要把空白字节去掉。
 * <p>用它就能让官方接口精确认出一个本地 jar 是哪个项目（{@code POST /v1/fingerprints}），
 * 比按名字猜 slug 准得多。</p>
 */
public final class Murmur2 {

    private static final int M = 0x5bd1e995;
    private static final int R = 24;

    private Murmur2() {
    }

    /** 计算一个 jar 的 CurseForge 指纹。 */
    public static int fingerprint(Path jar) {
        try (InputStream in = Files.newInputStream(jar)) {
            return fingerprint(in.readAllBytes());
        } catch (Exception error) {
            return 0;
        }
    }

    /** 去空白后做 MurmurHash2。 */
    public static int fingerprint(byte[] raw) {
        byte[] cleaned = new byte[raw.length];
        int length = 0;
        for (byte value : raw) {
            // CurseForge 会把 9/10/13/32 这四个空白字节剔掉再算
            if (value == 9 || value == 10 || value == 13 || value == 32) {
                continue;
            }
            cleaned[length++] = value;
        }
        return hash(cleaned, 0, length, 1);
    }

    /** 标准 MurmurHash2 32 位实现。 */
    public static int hash(byte[] data, int offset, int length, int seed) {
        int h = seed ^ length;
        int end = offset + (length & ~3);
        for (int index = offset; index < end; index += 4) {
            int k = (data[index] & 0xFF)
                    | ((data[index + 1] & 0xFF) << 8)
                    | ((data[index + 2] & 0xFF) << 16)
                    | ((data[index + 3] & 0xFF) << 24);
            k *= M;
            k ^= k >>> R;
            k *= M;
            h *= M;
            h ^= k;
        }
        switch (length & 3) {
            case 3:
                h ^= (data[end + 2] & 0xFF) << 16;
                // fall through
            case 2:
                h ^= (data[end + 1] & 0xFF) << 8;
                // fall through
            case 1:
                h ^= data[end] & 0xFF;
                h *= M;
                break;
            default:
                break;
        }
        h ^= h >>> 13;
        h *= M;
        h ^= h >>> 15;
        return h;
    }
}
