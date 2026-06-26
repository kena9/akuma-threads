package com.akumathreads.controller;

import com.akumathreads.dto.ProductCardDto;
import com.akumathreads.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;


/**
 * Dynamic sitemap so Google always indexes the current product catalogue.
 * Oliver's TikTok bio links directly to ollythreads.com — this ensures
 * every product URL Google sees is real and active.
 */
@Controller
@RequiredArgsConstructor
public class SitemapController {

    private static final String BASE_URL = "https://ollythreads.com";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final ProductService productService;

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public String sitemap() {

        // Fetch all active products (up to 500 — more than enough for a drops brand)
        List<ProductCardDto> products = productService
                .findFiltered(null, null, null, null,
                        PageRequest.of(0, 500, Sort.by("createdDate").descending()))
                .getContent();

        String today = LocalDate.now().format(DATE_FMT);

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        // ── Static pages ─────────────────────────────────────────────────────
        appendUrl(xml, BASE_URL + "/",          today, "daily",   "1.0");
        appendUrl(xml, BASE_URL + "/shop",      today, "daily",   "0.9");
        appendUrl(xml, BASE_URL + "/about",     today, "monthly", "0.7");

        // ── Product pages ─────────────────────────────────────────────────────
        for (ProductCardDto p : products) {
            String lastMod = p.getCreatedDate() != null
                    ? p.getCreatedDate().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).substring(0, 10)
                    : today;
            appendUrl(xml, BASE_URL + "/product/" + p.getId(), lastMod, "weekly", "0.8");
        }

        xml.append("</urlset>");
        return xml.toString();
    }

    private static void appendUrl(StringBuilder sb, String loc, String lastMod,
                                  String changeFreq, String priority) {
        sb.append("  <url>\n");
        sb.append("    <loc>").append(loc).append("</loc>\n");
        sb.append("    <lastmod>").append(lastMod).append("</lastmod>\n");
        sb.append("    <changefreq>").append(changeFreq).append("</changefreq>\n");
        sb.append("    <priority>").append(priority).append("</priority>\n");
        sb.append("  </url>\n");
    }
}
