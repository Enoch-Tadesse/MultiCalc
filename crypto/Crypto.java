package crypto;

import java.net.URI;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.sql.*;

/*
 * updateAssets -> used to add or update assets that the user currently hold
 * removeAsset -> used to remove assets that the user currently hold
 * getOwnedPrices -> returns Hash<String, Double> of the user asset along with current worth
 * getAvailableAssets -> used to fetch assets the user does not have
 *      should be checked before adding assets
 */

public class Crypto {
    private String baseApi = "https://api.coingecko.com/api/v3/simple/price?ids=";
    private String defaultCurrency = "&vs_currencies=usd";
    private ArrayList<String> allCurrencies = new ArrayList<>(
            Arrays.asList("bitcoin", "ethereum", "binancecoin", "solana", "ripple", "dogecoin", "toncoin"));
    private static final String DB_NAME;
    private static final String DB_PASSWORD;
    private static final String DB_USER;
    private static final String TABLE_NAME;
    private static final String DB_PORT;
    private static final String DB_HOST;

    static {
        DB_NAME = System.getenv("DB_NAME");
        DB_PASSWORD = System.getenv("DB_PASSWORD");
        DB_USER = System.getenv("DB_USER");
        DB_HOST = System.getenv("DB_HOST");
        DB_PORT = System.getenv("DB_PORT");
        TABLE_NAME = System.getenv("TABLE_NAME");
    }

    public Crypto() {
        if (DB_NAME == null)
            throw new Error("database name is required");
        if (DB_USER == null)
            throw new Error("database username is required");
        if (DB_PASSWORD == null)
            throw new Error("database password is required");
        if (TABLE_NAME == null)
            throw new Error("table name can not be empty");
        if (DB_PORT == null)
            throw new Error("port can not be empyt");
        if (DB_HOST == null)
            throw new Error("host can not be empyt");

    }

    private Connection connect() throws SQLException {
        // makes sure to connect to the database
        String url = "jdbc:mysql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME;
        return DriverManager.getConnection(url, DB_USER, DB_PASSWORD);
    }

    private Boolean isValidAsset(String asset) {
        return allCurrencies.contains(asset);
    }

    public void removeAsset(String asset) throws SQLException {
        // deletes asset from the database
        if (!(isValidAsset(asset)))
            throw new Error("invalid asset name");
        try (Connection conn = connect()) {
            String sql = "DELETE FROM " + TABLE_NAME + " WHERE currency = ?";
            PreparedStatement query = conn.prepareStatement(sql);
            query.setString(1, asset);
            query.executeUpdate();
        }
    }

    public void updateAssets(String asset, Double quantity) throws SQLException {
        // updates an asset from the database
        // its meant to update and also add assets
        if (!(isValidAsset(asset)))
            throw new Error("Invalid asset name");
        try (Connection conn = connect()) {
            String sql = "INSERT INTO " + TABLE_NAME + " (currency, quantity) VALUES (?, ?) " +
                    "ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity)";
            PreparedStatement query = conn.prepareStatement(sql);
            query.setString(1, asset);
            query.setDouble(2, quantity);
            query.executeUpdate();
        }
    }

    private HashMap<String, Double> getOwnedWithQuantity() throws SQLException {
        // returns all owned asset along with their quantity
        HashMap<String, Double> assetMap = new HashMap<>();
        try (Connection conn = connect()) {
            String sql = "SELECT currency, quantity FROM " + TABLE_NAME;
            PreparedStatement query = conn.prepareStatement(sql);
            ResultSet rs = query.executeQuery();
            while (rs.next()) {
                assetMap.put(rs.getString("currency"), rs.getDouble("quantity"));
            }
        }
        return assetMap;
    }

    public ArrayList<String> getAvailableAssets() throws SQLException {
        // returns assets that the user currently does not own
        ArrayList<String> availableCurrencies = new ArrayList<>();
        HashMap<String, Double> ownedCurrencies = getOwnedWithQuantity();
        HashSet<String> ownedSet = new HashSet<>(ownedCurrencies.keySet());
        for (String currency : allCurrencies) {
            if (!ownedSet.contains(currency))
                availableCurrencies.add(currency);
        }
        return availableCurrencies;
    }

    public HashMap<String, Double> getOwnedPrices() throws SQLException, IOException, InterruptedException {
        HashMap<String, Double> assets = getOwnedWithQuantity();
        // check for the size of the assets
        if (assets.size() == 0) {
            return new HashMap<>();
        }

        // build the request api string
        StringBuilder requestApi = new StringBuilder(baseApi);
        requestApi.append(String.join(",", assets.keySet()));
        requestApi.append(defaultCurrency);

        // create a connection
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(requestApi.toString()))
                .GET()
                .build();

        // send a request
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String json = response.body();

        HashMap<String, Double> pricePair = new HashMap<>();
        // iterate over the json and extract data
        for (String currency : assets.keySet()) {
            Double quantity = assets.get(currency);

            String key = "\"" + currency + "\":{\"usd\":";
            int start = json.indexOf(key);
            start += key.length();
            int end = json.indexOf("}", start);
            String priceStr = json.substring(start, end);
            Double priceDouble = Double.parseDouble(priceStr);

            pricePair.put(currency, priceDouble * quantity);
        }
        return pricePair;
    }
}
