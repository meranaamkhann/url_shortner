package com.urlshortener.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;

/**
 * Generates QR codes for short URLs (Functional Requirement: QR code generation).
 * Uses Google's ZXing — the de facto standard JVM library for this — rather than
 * hand-rolling QR matrix encoding, which is exactly the kind of well-solved problem
 * not worth reimplementing.
 */
@Service
public class QrCodeService {

    private static final int DEFAULT_SIZE_PX = 300;

    public byte[] generatePng(String content, int sizePx) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN, 1
            );
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("Failed to generate QR code", e);
        }
    }

    public byte[] generatePng(String content) {
        return generatePng(content, DEFAULT_SIZE_PX);
    }

    public String generateBase64Png(String content) {
        return Base64.getEncoder().encodeToString(generatePng(content));
    }
}
