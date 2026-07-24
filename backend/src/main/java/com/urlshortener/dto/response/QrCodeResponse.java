package com.urlshortener.dto.response;

/** base64PngImage is a data-URI-ready base64 string; the controller also exposes a raw PNG endpoint. */
public record QrCodeResponse(
        String shortUrl,
        String base64PngImage
) {
}
