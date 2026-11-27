package com.wardrobe.service;

import com.wardrobe.model.entity.WardrobeItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-JDK Visual Contact Sheet (Set-of-Mark grid) composer for VLM visual outfit styling.
 * Stitches up to 16 candidate wardrobe items into a single compact 1024x1024 JPEG grid labeled [#1..#N]
 * so a Vision-Language Model can inspect real garment colors, textures, silhouettes, and prints in 1 API call
 * without native libraries or extra RAM overhead on a 2GB server.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutfitContactSheetService {
    private static final Map<String, String> CAT_LABELS = Map.of(
            "outerwear", "OUTER",
            "top", "TOP",
            "bottom", "BOTTOM",
            "dress", "DRESS",
            "shoes", "SHOES",
            "bag", "BAG",
            "accessory", "ACC"
    );

    private final StorageService storageService;

    public record ContactSheet(
            String dataUri,
            int realImageCount,
            int totalTiles,
            Map<Integer, Long> tagToItemId
    ) {}

    public ContactSheet buildContactSheet(Long userId, List<WardrobeItem> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        List<WardrobeItem> tiles = candidates.size() > 12 ? candidates.subList(0, 12) : candidates;
        int n = tiles.size();
        int cols = n <= 4 ? 2 : 3;
        int rows = (int) Math.ceil((double) n / cols);
        int tileW = cols == 2 ? 480 : 340;
        int tileH = tileW;
        int canvasW = tileW * cols;
        int canvasH = tileH * rows;

        BufferedImage sheet = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, canvasW, canvasH);

        Map<Integer, Long> tagMap = new LinkedHashMap<>();
        int realImageCount = 0;

        for (int i = 0; i < n; i++) {
            WardrobeItem item = tiles.get(i);
            int num = i + 1;
            tagMap.put(num, item.getId());

            int col = i % cols;
            int row = i / cols;
            int x0 = col * tileW;
            int y0 = row * tileH;
            int pad = 10;
            int innerX = x0 + pad;
            int innerY = y0 + pad;
            int innerW = tileW - pad * 2;
            int innerH = tileH - pad * 2;

            // Clean neutral studio tile well
            g.setColor(new Color(0xF6, 0xF6, 0xF6));
            g.fillRoundRect(innerX, innerY, innerW, innerH, 16, 16);
            g.setColor(new Color(0xE5, 0xE5, 0xE5));
            g.drawRoundRect(innerX, innerY, innerW, innerH, 16, 16);

            // Try to decode real uploaded PNG/JPG garment photo
            BufferedImage garmentImg = loadGarmentImage(userId, item);
            int imgAreaTop = innerY + 34;
            int imgAreaH = innerH - 68;
            int imgAreaW = innerW - 20;

            if (garmentImg != null) {
                realImageCount++;
                double ratio = Math.min((double) imgAreaW / garmentImg.getWidth(), (double) imgAreaH / garmentImg.getHeight());
                int dw = Math.max(16, (int) Math.round(garmentImg.getWidth() * ratio));
                int dh = Math.max(16, (int) Math.round(garmentImg.getHeight() * ratio));
                int dx = innerX + (innerW - dw) / 2;
                int dy = imgAreaTop + (imgAreaH - dh) / 2;
                g.drawImage(garmentImg, dx, dy, dw, dh, null);
            } else {
                // Visual color swatch fallback for SVG/WebP seeded items so the VLM still sees exact color & silhouette cue
                Color swatch = parseHexColor(item.getColorHex());
                int sw = Math.min(imgAreaW - 24, imgAreaH - 16);
                int sx = innerX + (innerW - sw) / 2;
                int sy = imgAreaTop + (imgAreaH - sw) / 2;
                g.setColor(swatch);
                g.fillRoundRect(sx, sy, sw, sw, 20, 20);
                g.setColor(new Color(0, 0, 0, 35));
                g.drawRoundRect(sx, sy, sw, sw, 20, 20);

                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
                String catTag = CAT_LABELS.getOrDefault(item.getCategory(), "ITEM");
                FontMetrics fm = g.getFontMetrics();
                g.setColor(isDark(swatch) ? Color.WHITE : new Color(0x22, 0x22, 0x22));
                g.drawString(catTag, sx + (sw - fm.stringWidth(catTag)) / 2, sy + sw / 2 + fm.getAscent() / 2 - 2);
            }

            // High-contrast Set-of-Mark badge [#N] in top-left corner
            String badge = "#" + num;
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 15));
            FontMetrics bfm = g.getFontMetrics();
            int bw = bfm.stringWidth(badge) + 14;
            int bh = 24;
            g.setColor(Color.BLACK);
            g.fillRoundRect(innerX + 8, innerY + 8, bw, bh, 12, 12);
            g.setColor(Color.WHITE);
            g.drawString(badge, innerX + 15, innerY + 25);

            // Category tag top-right (flag one-piece dresses/JSKs/jumpsuits so VLM never pairs them with bottoms)
            String catTag = OutfitRecommendationService.isOnePieceEntity(item)
                    ? "ONE-PIECE"
                    : CAT_LABELS.getOrDefault(item.getCategory(), "ITEM");
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            FontMetrics cfm = g.getFontMetrics();
            g.setColor(new Color(0x66, 0x66, 0x66));
            g.drawString(catTag, innerX + innerW - cfm.stringWidth(catTag) - 10, innerY + 24);

            // Bottom caption (id + category/color)
            String caption = "ID:" + item.getId() + " " + safeTrim(item.getColor(), 6);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.setColor(new Color(0x33, 0x33, 0x33));
            g.drawString(caption, innerX + 10, innerY + innerH - 10);
        }

        g.dispose();

        try {
            byte[] jpegBytes = encodeJpeg(sheet, 0.82f);
            String dataUri = storageService.toDataUri(jpegBytes, "image/jpeg");
            return new ContactSheet(dataUri, realImageCount, n, tagMap);
        } catch (Exception e) {
            log.warn("Failed to encode outfit contact sheet: {}", e.getMessage());
            return null;
        }
    }

    private BufferedImage loadGarmentImage(Long userId, WardrobeItem item) {
        String[] urls = { item.getProcessedImageUrl(), item.getOriginalImageUrl() };
        for (String url : urls) {
            if (url == null || url.isBlank() || url.trim().startsWith("svg://")) continue;
            StorageService.StoredImage stored = storageService.readGarmentImageForAi(userId, url);
            if (stored == null || stored.bytes() == null) continue;
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(stored.bytes()));
                if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                    return img;
                }
            } catch (Exception ignored) {
                // Non-JDK format falls back to swatch tile cleanly
            }
        }
        return null;
    }

    private static byte[] encodeJpeg(BufferedImage img, float quality) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (writers.hasNext()) {
            ImageWriter writer = writers.next();
            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(ios);
                ImageWriteParam param = writer.getDefaultWriteParam();
                if (param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(quality);
                }
                writer.write(null, new IIOImage(img, null, null), param);
            } finally {
                writer.dispose();
            }
        } else {
            ImageIO.write(img, "jpg", out);
        }
        return out.toByteArray();
    }

    private static Color parseHexColor(String hex) {
        if (hex == null || hex.isBlank()) return new Color(0xDF, 0xD3, 0xC3);
        try {
            String clean = hex.trim().replace("#", "");
            if (clean.length() == 6) {
                return new Color(Integer.parseInt(clean, 16));
            }
        } catch (Exception ignored) {
        }
        return new Color(0xDF, 0xD3, 0xC3);
    }

    private static boolean isDark(Color c) {
        double lum = 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
        return lum < 135;
    }

    private static String safeTrim(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
