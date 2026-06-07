package com.trading.analysis;
// <dependency>
//     <groupId>org.springframework.boot</groupId>
//     <artifactId>spring-boot-starter-web</artifactId>
// </dependency>
// <dependency>
//     <groupId>com.anthropic</groupId>
//     <artifactId>anthropic-sdk-java</artifactId>
//     <version>0.1.0</version>
// </dependency>
// <dependency>
//     <groupId>org.projectlombok</groupId>
//     <artifactId>lombok</artifactId>
// </dependency>

// application.yml
// anthropic:
//   api-key: ${ANTHROPIC_API_KEY}
// finance:
//   api-key: ${FINANCE_API_KEY}
//   api-url: https://financialmodelingprep.com/api/v3



import com.anthropic.Anthropic;
import com.anthropic.models.*;
        import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
        import org.springframework.web.client.RestTemplate;

import java.util.*;
        import java.util.stream.Collectors;

@SpringBootApplication
public class StockAnalysisApplication {
    public static void main(String[] args) {
        SpringApplication.run(StockAnalysisApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public Anthropic anthropicClient(@Value("${anthropic.api-key}") String apiKey) {
        return Anthropic.builder().apiKey(apiKey).build();
    }
}

@Data
class StockData {
    private String ticker;
    private Double price;
    private Double changePercent;
    private Long volume;
    private Double marketCap;
    private Double peRatio;
    private String companyName;
}

@Data
class StockRanking {
    private String ticker;
    private String companyName;
    private int rank;
    private String rating;
    private String reasoning;
    private Double confidenceScore;
    private String tradingRecommendation;
}

@Data
class AnalysisRequest {
    private List<String> tickers;
    private String analysisType = "swing_trading"; // swing_trading, day_trading, long_term
}

@Data
class AnalysisResponse {
    private List<StockRanking> rankings;
    private String marketOverview;
    private String analysisTimestamp;
}

@Slf4j
@Service
@RequiredArgsConstructor
class FinanceDataService {
    private final RestTemplate restTemplate;

    @Value("${finance.api-key}")
    private String apiKey;

    @Value("${finance.api-url}")
    private String apiUrl;

    public StockData getStockData(String ticker) {
        try {
            String quoteUrl = String.format("%s/quote/%s?apikey=%s", apiUrl, ticker, apiKey);
            Map<String, Object>[] response = restTemplate.getForObject(quoteUrl, Map[].class);

            if (response != null && response.length > 0) {
                Map<String, Object> data = response[0];
                StockData stock = new StockData();
                stock.setTicker(ticker);
                stock.setCompanyName((String) data.get("name"));
                stock.setPrice(((Number) data.get("price")).doubleValue());
                stock.setChangePercent(((Number) data.get("changesPercentage")).doubleValue());
                stock.setVolume(((Number) data.get("volume")).longValue());
                stock.setMarketCap(((Number) data.getOrDefault("marketCap", 0)).doubleValue());
                stock.setPeRatio(((Number) data.getOrDefault("pe", 0)).doubleValue());
                return stock;
            }
        } catch (Exception e) {
            log.error("Error fetching data for ticker: {}", ticker, e);
        }
        return null;
    }

    public List<StockData> getMultipleStocks(List<String> tickers) {
        return tickers.stream()
                .map(this::getStockData)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}

@Slf4j
@Service
@RequiredArgsConstructor
class ClaudeAnalysisService {
    private final Anthropic anthropicClient;

    public AnalysisResponse analyzeStocks(List<StockData> stockData, String analysisType) {
        String prompt = buildAnalysisPrompt(stockData, analysisType);

        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model("claude-sonnet-4-5-20250929")
                    .maxTokens(4096)
                    .message(UserMessage.of(prompt))
                    .build();

            Message response = anthropicClient.messages().create(params);
            String analysisText = extractTextFromResponse(response);

            return parseClaudeResponse(analysisText, stockData);
        } catch (Exception e) {
            log.error("Error calling Claude API", e);
            throw new RuntimeException("Failed to analyze stocks", e);
        }
    }

    private String buildAnalysisPrompt(List<StockData> stocks, String analysisType) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an expert financial analyst. Analyze the following stocks for ")
                .append(analysisType).append(" and rank them from best to worst for trading.\n\n");

        prompt.append("Stock Data:\n");
        for (StockData stock : stocks) {
            prompt.append(String.format("- %s (%s): Price=$%.2f, Change=%.2f%%, Volume=%d, P/E=%.2f, MarketCap=$%.0f\n",
                    stock.getTicker(), stock.getCompanyName(), stock.getPrice(),
                    stock.getChangePercent(), stock.getVolume(), stock.getPeRatio(), stock.getMarketCap()));
        }

        prompt.append("\nProvide your analysis in the following format:\n");
        prompt.append("RANKINGS:\n");
        prompt.append("For each stock provide: RANK|TICKER|RATING|CONFIDENCE|RECOMMENDATION|REASONING\n");
        prompt.append("Where RATING is (Strong Buy/Buy/Hold/Sell/Strong Sell), CONFIDENCE is 0-100, ");
        prompt.append("RECOMMENDATION is brief action, and REASONING is your analysis.\n\n");
        prompt.append("MARKET_OVERVIEW:\n");
        prompt.append("Provide overall market sentiment and key trends.\n");

        return prompt.toString();
    }

    private String extractTextFromResponse(Message response) {
        return response.content().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).text())
                .collect(Collectors.joining("\n"));
    }

    private AnalysisResponse parseClaudeResponse(String analysisText, List<StockData> stocks) {
        AnalysisResponse response = new AnalysisResponse();
        response.setAnalysisTimestamp(new Date().toString());

        List<StockRanking> rankings = new ArrayList<>();
        String[] lines = analysisText.split("\n");
        boolean inRankings = false;
        boolean inOverview = false;
        StringBuilder overview = new StringBuilder();

        for (String line : lines) {
            if (line.contains("RANKINGS:")) {
                inRankings = true;
                inOverview = false;
                continue;
            }
            if (line.contains("MARKET_OVERVIEW:")) {
                inOverview = true;
                inRankings = false;
                continue;
            }

            if (inRankings && line.contains("|")) {
                String[] parts = line.split("\\|");
                if (parts.length >= 6) {
                    StockRanking ranking = new StockRanking();
                    ranking.setRank(Integer.parseInt(parts[0].trim()));
                    ranking.setTicker(parts[1].trim());
                    ranking.setRating(parts[2].trim());
                    ranking.setConfidenceScore(Double.parseDouble(parts[3].trim()));
                    ranking.setTradingRecommendation(parts[4].trim());
                    ranking.setReasoning(parts[5].trim());

                    stocks.stream()
                            .filter(s -> s.getTicker().equals(ranking.getTicker()))
                            .findFirst()
                            .ifPresent(s -> ranking.setCompanyName(s.getCompanyName()));

                    rankings.add(ranking);
                }
            }

            if (inOverview && !line.trim().isEmpty()) {
                overview.append(line).append("\n");
            }
        }

        response.setRankings(rankings);
        response.setMarketOverview(overview.toString().trim());

        return response;
    }
}

@Slf4j
@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
class StockAnalysisController {
    private final FinanceDataService financeService;
    private final ClaudeAnalysisService claudeService;

    @PostMapping("/analyze")
    public AnalysisResponse analyzeStocks(@RequestBody AnalysisRequest request) {
        log.info("Analyzing stocks: {}", request.getTickers());

        List<StockData> stockData = financeService.getMultipleStocks(request.getTickers());

        if (stockData.isEmpty()) {
            throw new RuntimeException("Could not fetch data for any tickers");
        }

        return claudeService.analyzeStocks(stockData, request.getAnalysisType());
    }

    @GetMapping("/quote/{ticker}")
    public StockData getQuote(@PathVariable String ticker) {
        StockData data = financeService.getStockData(ticker);
        if (data == null) {
            throw new RuntimeException("Could not fetch data for ticker: " + ticker);
        }
        return data;
    }
}