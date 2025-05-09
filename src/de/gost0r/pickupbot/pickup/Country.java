package de.gost0r.pickupbot.pickup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;

import org.json.JSONArray;

public class Country {

	private static HashMap<String, String> CountryToContinentMap = new HashMap<String, String>();
	
	public static void initCountryCodes() throws IOException {
		Path filePath = Paths.get("country-and-continent-codes-list.json");
		String jsonTxt = Files.readString(filePath, StandardCharsets.UTF_8);

		JSONArray arr = new JSONArray(jsonTxt);
		
		for(int i = 0; i < arr.length(); i++) {
			String continent = arr.getJSONObject(i).get("Continent_Code").toString();
			String country = arr.getJSONObject(i).get("Two_Letter_Country_Code").toString();
			
			CountryToContinentMap.put(country, continent);
		}
	}
	
	public static String getContinent(String country) {
		return CountryToContinentMap.get(country);
	}
	
	public static Boolean isValid(String country) {
		Object o = CountryToContinentMap.get(country);

		return o != null;
	}
	
	public static String getCountryFlag(String country) {
		String msg;
		
		if( country.equalsIgnoreCase("NOT_DEFINED")) {
			msg = "";
		}
		else {
			msg = ":flag_" + country.toLowerCase() + ":";
		}
		
		return msg;
	}
	
}
