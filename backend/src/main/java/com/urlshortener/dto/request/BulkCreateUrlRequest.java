package com.urlshortener.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record BulkCreateUrlRequest(

        @NotEmpty(message = "items must not be empty")
        @Size(max = 100, message = "A maximum of 100 URLs can be created in a single bulk request")
        @Valid
        List<CreateUrlRequest> items
) {
}
