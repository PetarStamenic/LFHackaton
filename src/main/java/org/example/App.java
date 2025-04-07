package org.example;

import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class App {

    private static final String APPLICATION_NAME = "Google Calendar API Java";
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final String TOKENS_DIRECTORY_PATH = "tokens";
    private static final List<String> SCOPES = Arrays.asList(CalendarScopes.CALENDAR, CalendarScopes.CALENDAR_EVENTS);
    private static final String CREDENTIALS_FILE_PATH = "/credentials.json";

    private static final String API_KEY = "REDACTED";
    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=" + API_KEY;
    private static final String prompt = "You are a helpful assistant. You can only respond with the function name that I can call. Do not provide any other information or context. The function names are: list today's events, create event and invite, deny event by ID, accept event by ID, remove accepted event by ID, invite to event, get next event and Meet link, get not accepted events, get upcoming events.";


    public static void main(String... args) throws IOException, GeneralSecurityException {
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        Calendar service = getCalendarService(HTTP_TRANSPORT);

        Scanner scanner = new Scanner(System.in);
        String input;
        do {

            System.out.print("Enter your command: ");
            input = scanner.nextLine();
            if (input.isEmpty()) {
                continue; // Ignore empty input
            }
            if(input.equalsIgnoreCase("askGemini")) {
                askGemini();
                continue; // Ignore empty input
            }
            if (!input.equalsIgnoreCase("exit")) {
                System.out.println("Calling Gemini API...");
                System.out.println("Prompt: " + input);
                String functionToCall = getFunctionFromGemini(prompt + input);
                callFunction(service, functionToCall, scanner);
            }
        } while (!input.equalsIgnoreCase("exit"));

        scanner.close();
    }

    private static String getFunctionFromGemini(String text) throws IOException {
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(GEMINI_API_URL);
        httpPost.setHeader("Content-Type", "application/json");

        JsonObject requestBody = new JsonObject();
        JsonObject content = new JsonObject();
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        content.add("parts", part);
        requestBody.add("contents", content);

        StringEntity entity = new StringEntity(requestBody.toString());
        httpPost.setEntity(entity);

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            String responseString = EntityUtils.toString(response.getEntity());
            JsonObject jsonResponse = JsonParser.parseString(responseString).getAsJsonObject();
            System.out.println(jsonResponse);

            // Define a mapping of possible functions
            Map<String, String> functionMapping = new HashMap<>();
            functionMapping.put("list today's events", "getTodaysEvents");
            functionMapping.put("create event and invite", "createEventAndInvite");
            functionMapping.put("deny event by ID", "denyEventById");
            functionMapping.put("accept event by ID", "acceptEventById");
            functionMapping.put("remove accepted event by ID", "removeAcceptedEventById");
            functionMapping.put("invite to event", "inviteToEvent");
            functionMapping.put("get next event and Meet link", "getNextEventAndMeetLink");
            functionMapping.put("get not accepted events", "getNotAcceptedEvents");
            functionMapping.put("get upcoming events", "getUpcomingEvents");

            // Extract the function name from the response
            String responseText = jsonResponse.getAsJsonArray("candidates")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString().toLowerCase();

            // Match the response text to the function name
            for (Map.Entry<String, String> entry : functionMapping.entrySet()) {
                if (responseText.toLowerCase().contains(entry.getKey().toLowerCase())) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }

    private static void callFunction(Calendar service, String functionToCall, Scanner scanner) throws IOException {
        System.out.println("Calling function: " + functionToCall);
        switch (functionToCall) {
            case "createEventAndInvite":
                createEventAndInvite(service, scanner);
                break;
            case "denyEventById":
                denyEventById(service, scanner);
                break;
            case "acceptEventById":
                acceptEventById(service, scanner);
                break;
            case "removeAcceptedEventById":
                removeAcceptedEventById(service, scanner);
                break;
            case "inviteToEvent":
                inviteToEvent(service, scanner);
                break;
            case "getNextEventAndMeetLink":
                getNextEventAndMeetLink(service);
                break;
            case "getTodaysEvents":
                getTodaysEvents(service);
                break;
            case "getNotAcceptedEvents":
                List<Event> notAcceptedEvents = getNotAcceptedEvents(service);
                printEvents(notAcceptedEvents);
                break;
            case "getUpcomingEvents":
                List<Event> upcomingEvents = getUpcomingEvents(service);
                printEvents(upcomingEvents);
                break;
            default:
                System.out.println("Invalid function: " + functionToCall);
        }
    }

    private static void printEvents(List<Event> events, String... args) throws IOException {
        if (args.length > 0 && args[0].equals("debug")) {
            printEventJsons(events);
        } else {
            DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm");
            for (Event event : events) {
                printEventDetails(event, timeFormat);
            }
        }
    }

    // Method to print event JSONs
    private static void printEventJsons(List<Event> events) throws IOException {
        for (Event event : events) {
            System.out.println("####################################");
            System.out.println("####################################");
            System.out.println("####################################");
            System.out.println(event.toPrettyString());
            System.out.println("####################################");
            System.out.println("####################################");
            System.out.println("####################################");
        }
    }


    private static void denyEventById(Calendar service, Scanner scanner) throws IOException {
        System.out.println("Enter the event ID to deny:");
        String eventId = scanner.next();

        Event event = service.events().get("primary", eventId).execute();
        List<EventAttendee> attendees = event.getAttendees();
        if (attendees != null) {
            for (EventAttendee attendee : attendees) {
                attendee.setResponseStatus("declined");
            }
            event.setAttendees(attendees);
            service.events().update("primary", event.getId(), event).execute();
            System.out.println("Event denied: " + event.getSummary());
        } else {
            System.out.println("No attendees found for this event.");
        }
    }

    // Method to create a new event and invite attendees
    private static void createEventAndInvite(Calendar service, Scanner scanner) throws IOException {
        System.out.println("Enter the event summary (title):");
        String summary = scanner.nextLine().trim();
        while (summary.isEmpty()) {
            System.out.println("Event summary cannot be empty. Please enter the event summary (title):");
            summary = scanner.nextLine().trim();
        }

        System.out.println("Enter the event description:");
        String description = scanner.nextLine().trim();
        while (description.isEmpty()) {
            System.out.println("Event description cannot be empty. Please enter the event description:");
            description = scanner.nextLine().trim();
        }

        System.out.println("Enter the event location:");
        String location = scanner.nextLine().trim();
        while (location.isEmpty()) {
            System.out.println("Event location cannot be empty. Please enter the event location:");
            location = scanner.nextLine().trim();
        }

        System.out.println("Enter the event start time (Unix time in milliseconds):");
        while (!scanner.hasNextLong()) {
            System.out.println("Invalid input. Please enter a valid Unix time in milliseconds:");
            scanner.next(); // Clear the invalid input
        }
        long startTimeMillis = scanner.nextLong();

        System.out.println("Enter the event end time (Unix time in milliseconds):");
        while (!scanner.hasNextLong()) {
            System.out.println("Invalid input. Please enter a valid Unix time in milliseconds:");
            scanner.next(); // Clear the invalid input
        }
        long endTimeMillis = scanner.nextLong();

        Event event = new Event()
                .setSummary(summary)
                .setDescription(description)
                .setLocation(location)
                .setStart(new EventDateTime().setDateTime(new DateTime(startTimeMillis)).setTimeZone("UTC"))
                .setEnd(new EventDateTime().setDateTime(new DateTime(endTimeMillis)).setTimeZone("UTC"));

        System.out.println("Enter the email addresses of attendees (comma separated):");
        String[] emails = scanner.next().split(",");
        List<EventAttendee> attendees = new ArrayList<>();
        for (String email : emails) {
            attendees.add(new EventAttendee().setEmail(email.trim()));
        }
        event.setAttendees(attendees);

        event = service.events().insert("primary", event).execute();
        System.out.println("Event created: " + event.getHtmlLink());
    }

    // Method to get the next event and its Google Meet link
    private static void getNextEventAndMeetLink(Calendar service) throws IOException {
        DateTime now = new DateTime(System.currentTimeMillis());

        Events events = service.events().list("primary")
                .setMaxResults(1)
                .setOrderBy("startTime")
                .setSingleEvents(true)
                .setTimeMin(now)
                .execute();

        List<Event> items = events.getItems();
        if (items.isEmpty()) {
            System.out.println("No upcoming events found.");
        } else {
            Event event = items.get(0);
            System.out.println("Next event: " + event.getSummary());

            DateTime start = event.getStart().getDateTime();
            DateTime end = event.getEnd().getDateTime();
            if (start != null && end != null) {
                LocalDateTime startLdt = Instant.ofEpochMilli(start.getValue()).atZone(ZoneId.systemDefault()).toLocalDateTime();
                LocalDateTime endLdt = Instant.ofEpochMilli(end.getValue()).atZone(ZoneId.systemDefault()).toLocalDateTime();
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

                System.out.println("Start time: " + startLdt.format(formatter));
                System.out.println("End time: " + endLdt.format(formatter));
            }

            String meetLink = event.getHangoutLink();
            if (meetLink != null) {
                System.out.println("Google Meet link: " + meetLink);
            } else {
                System.out.println("No Google Meet link available for this event.");
            }
        }
    }
    // Method to accept an event by ID
    private static void acceptEventById(Calendar service, Scanner scanner) throws IOException {
        System.out.println("Enter the event ID to accept:");
        String eventId = scanner.next();

        Event event = service.events().get("primary", eventId).execute();
        List<EventAttendee> attendees = event.getAttendees();
        if (attendees != null) {
            for (EventAttendee attendee : attendees) {
                if ("needsAction".equals(attendee.getResponseStatus())) {
                    attendee.setResponseStatus("accepted");
                }
            }
            event.setAttendees(attendees);
            service.events().update("primary", event.getId(), event).execute();
            System.out.println("Event accepted: " + event.getSummary());
        } else {
            System.out.println("No attendees found for this event.");
        }
    }

    // Method to remove an accepted event by ID
    private static void removeAcceptedEventById(Calendar service, Scanner scanner) throws IOException {
        System.out.println("Enter the event ID to remove:");
        String eventId = scanner.next();

        Event event = service.events().get("primary", eventId).execute();
        List<EventAttendee> attendees = event.getAttendees();
        if (attendees != null) {
            attendees.removeIf(attendee -> "accepted".equals(attendee.getResponseStatus()));
            event.setAttendees(attendees);
            service.events().update("primary", event.getId(), event).execute();
            System.out.println("Accepted attendees removed from event: " + event.getSummary());
        } else {
            System.out.println("No attendees found for this event.");
        }
    }

    private static List<Event> getNotAcceptedEvents(Calendar service) throws IOException {
        DateTime now = new DateTime(System.currentTimeMillis());
        DateTime endOfDay = new DateTime(LocalDate.now().atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());

        Events events = service.events().list("primary")
                .setTimeMin(now)
                .setTimeMax(endOfDay)
                .setOrderBy("startTime")
                .setSingleEvents(true)
                .execute();

        List<Event> notAcceptedEvents = new ArrayList<>();
        for (Event event : events.getItems()) {
            List<EventAttendee> attendees = event.getAttendees();
            if (attendees != null) {
                for (EventAttendee attendee : attendees) {
                    if ("needsAction".equals(attendee.getResponseStatus())) {
                        notAcceptedEvents.add(event);
                        break;
                    }
                }
            }
        }
        return notAcceptedEvents;
    }

    private static void inviteToEvent(Calendar service, Scanner scanner) throws IOException {
        System.out.println("Enter the event ID to invite someone to:");
        String eventId = scanner.next();
        System.out.println("Enter the email address of the person to invite:");
        String email = scanner.next();

        Event event = service.events().get("primary", eventId).execute();
        EventAttendee attendee = new EventAttendee().setEmail(email);
        List<EventAttendee> attendees = event.getAttendees();
        if (attendees == null) {
            attendees = new ArrayList<>();
        }
        attendees.add(attendee);
        event.setAttendees(attendees);

        try {
            Event updatedEvent = service.events().update("primary", event.getId(), event).execute();
        } catch (GoogleJsonResponseException e) {
            System.out.println("Error: " + e.getDetails());
        }
        //System.out.println("Invitation sent to " + email + " for event: " + updatedEvent.getSummary());
    }

    private static void getTodaysEvents(Calendar service) throws IOException {
        DateTime now = new DateTime(System.currentTimeMillis());
        DateTime endOfDay = new DateTime(LocalDate.now().atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());

        Events events = service.events().list("primary")
                .setTimeMin(now)
                .setTimeMax(endOfDay)
                .setOrderBy("startTime")
                .setSingleEvents(true)
                .execute();

        // read files into strings for github_ccs_team_prs.json github_my_prs.json jira_mentions_last_24h.json jira_sprint_tasks_with_prs.json
        String githubCcsTeamPrs = new String(Files.readAllBytes(Paths.get("src/main/resources/github_ccs_team_prs.json")), StandardCharsets.UTF_8);
        String githubMyPrs = new String(Files.readAllBytes(Paths.get("src/main/resources/github_my_prs.json")), StandardCharsets.UTF_8);
        String jiraMentionsLast24h = new String(Files.readAllBytes(Paths.get("src/main/resources/jira_mentions_last_24h.json")), StandardCharsets.UTF_8);
        String jiraSprintTasksWithPrs = new String(Files.readAllBytes(Paths.get("src/main/resources/jira_sprint_tasks_with_prs.json")), StandardCharsets.UTF_8);

        String combinedPrompt =
                "You are a productivity assistant. I will provide you with several JSON blocks containing data about GitHub pull requests and Jira tickets. Please summarize the activity for today:\n" +
                "\n" +
                "List all PRs that were created, updated, merged, or closed today. For each, show:\n" +
                "\n" +
                "PR title and number\n" +
                "\n" +
                "Author\n" +
                "\n" +
                "Status (open/closed/merged)\n" +
                "\n" +
                "Repo and branch info\n" +
                "\n" +
                "If it's waiting for your review (your-github-username)\n" +
                "\n" +
                "Summarize any Jira issues or support/feedback tickets that were:\n" +
                "\n" +
                "Updated or due today\n" +
                "\n" +
                "Mentioned you in comments (e.g., \"@Your Name\")\n" +
                "\n" +
                "Are still open or recently resolved\n" +
                "\n" +
                "Output a clear, organized summary under headers like: \uD83D\uDD27 Pull Request Updates, \uD83D\uDCDD Jira Tasks, \uD83D\uDCEC Support & Feedback\n" +
                "\n" +
                "Here's the data:";

        sendPromptToGemini(combinedPrompt + githubCcsTeamPrs + githubMyPrs + jiraMentionsLast24h + jiraSprintTasksWithPrs);

        printEvents(events.getItems());
    }

    public static void askGemini() throws IOException {
        Scanner scanner = new Scanner(System.in);
        String githubCcsTeamPrs = new String(Files.readAllBytes(Paths.get("src/main/resources/github_ccs_team_prs.json")), StandardCharsets.UTF_8);
        String githubMyPrs = new String(Files.readAllBytes(Paths.get("src/main/resources/github_my_prs.json")), StandardCharsets.UTF_8);
        String jiraMentionsLast24h = new String(Files.readAllBytes(Paths.get("src/main/resources/jira_mentions_last_24h.json")), StandardCharsets.UTF_8);
        String jiraSprintTasksWithPrs = new String(Files.readAllBytes(Paths.get("src/main/resources/jira_sprint_tasks_with_prs.json")), StandardCharsets.UTF_8);


        System.out.print("Enter your prompt: ");
        String userPrompt = scanner.nextLine();
        if (userPrompt.isEmpty()) {
            askGemini(); // Ignore empty input
        }
        sendPromptToGemini("In these files " + userPrompt + githubCcsTeamPrs + githubMyPrs + jiraMentionsLast24h + jiraSprintTasksWithPrs + " can you " + userPrompt);
    }

    public static void sendPromptToGemini(String prompt) throws IOException {
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(GEMINI_API_URL);
        httpPost.setHeader("Content-Type", "application/json");

        JsonObject requestBody = new JsonObject();
        JsonObject content = new JsonObject();
        JsonObject part = new JsonObject();
        part.addProperty("text", prompt);
        content.add("parts", part);
        requestBody.add("contents", content);

        StringEntity entity = new StringEntity(requestBody.toString());
        httpPost.setEntity(entity);

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            String responseString = EntityUtils.toString(response.getEntity());
            JsonObject jsonResponse = JsonParser.parseString(responseString).getAsJsonObject();
            String relevantPart = jsonResponse.getAsJsonArray("candidates")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString();
            System.out.println(relevantPart);
            String responseFilePath = "src/main/resources/response.md";
            Files.write(Paths.get(responseFilePath), relevantPart.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static com.google.api.client.auth.oauth2.Credential getCredentials(NetHttpTransport HTTP_TRANSPORT) throws IOException {
        InputStream in = GoogleCalendarClient.class.getResourceAsStream(CREDENTIALS_FILE_PATH);
        if (in == null) {
            throw new FileNotFoundException("Resource not found: " + CREDENTIALS_FILE_PATH);
        }
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
                .setAccessType("offline")
                .build();

        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }

    private static Calendar getCalendarService(NetHttpTransport HTTP_TRANSPORT) throws IOException, GeneralSecurityException {
        return new Calendar.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    private static List<Event> getUpcomingEvents(Calendar service) throws IOException {
        Events events = service.events().list("primary")
                .setMaxResults(10)
                .setOrderBy("startTime")
                .setSingleEvents(true)
                .setTimeMin(new DateTime(System.currentTimeMillis()))
                .execute();
        return events.getItems();
    }

    private static void printEventsGroupedByDay(List<Event> events) throws IOException {
        if (events.isEmpty()) {
            System.out.println("No upcoming events found.");
        } else {
            System.out.println("Upcoming events grouped by day (next 7 days):");

            Map<LocalDate, List<Event>> eventsByDate = new TreeMap<>();
            for (Event event : events) {
                DateTime start = event.getStart().getDateTime();
                DateTime end = event.getEnd().getDateTime();
                if (start == null || end == null) continue;

                LocalDate date = Instant.ofEpochMilli(start.getValue())
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate();
                eventsByDate.computeIfAbsent(date, k -> new ArrayList<>()).add(event);
            }

            DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm");
            for (Map.Entry<LocalDate, List<Event>> entry : eventsByDate.entrySet()) {
                System.out.println("\n📅 " + entry.getKey().format(DateTimeFormatter.ofPattern("EEEE, MMMM dd")));
                for (Event event : entry.getValue()) {
                    printEventDetails(event, timeFormat);
                }
            }
        }
    }

    private static void printEventDetails(Event event, DateTimeFormatter timeFormat) throws IOException {
        DateTime start = event.getStart().getDateTime();
        DateTime end = event.getEnd().getDateTime();
        LocalDateTime startLdt = Instant.ofEpochMilli(start.getValue()).atZone(ZoneId.systemDefault()).toLocalDateTime();
        LocalDateTime endLdt = Instant.ofEpochMilli(end.getValue()).atZone(ZoneId.systemDefault()).toLocalDateTime();
        Duration duration = Duration.between(startLdt, endLdt);
        String formattedDuration = String.format("%d:%02d", duration.toHours(), duration.toMinutesPart());

        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy");
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("hh:mm a");
        String responseFilePath = "src/main/resources/response.md";
        String out = "***";
        System.out.println(out);
        Files.write(Paths.get(responseFilePath), "\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        Files.write(Paths.get(responseFilePath), out.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        out = "🕒 " + startLdt.format(timeFormatter) + " - " + endLdt.format(timeFormatter) + " (" + formattedDuration + ")\n";
        System.out.printf(out);
        Files.write(Paths.get(responseFilePath), "\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        Files.write(Paths.get(responseFilePath), out.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        out = "📍 " + event.getLocation() + "\n";
        System.out.printf(out);
        Files.write(Paths.get(responseFilePath), "\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        Files.write(Paths.get(responseFilePath), out.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        out = "📅 " + startLdt.format(dateFormatter) + "\n";
        System.out.printf(out);
        Files.write(Paths.get(responseFilePath), "\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        Files.write(Paths.get(responseFilePath), out.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        out = "📝 " + event.getSummary() + "\n";
        System.out.printf(out);
        Files.write(Paths.get(responseFilePath), "\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        Files.write(Paths.get(responseFilePath), out.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        out = "🔗 " + event.getHtmlLink() + "\n";
        System.out.printf(out);
        Files.write(Paths.get(responseFilePath), "\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        Files.write(Paths.get(responseFilePath), out.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        out = "🆔 Event ID: " + event.getId();
        System.out.printf(out);
        Files.write(Paths.get(responseFilePath), "\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        Files.write(Paths.get(responseFilePath), out.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);



        List<EventAttendee> attendees = event.getAttendees();
        if (attendees != null && !attendees.isEmpty()) {
            out = "👥 Attendees:";
            System.out.println(out);
            Files.write(Paths.get(responseFilePath), "\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
            Files.write(Paths.get(responseFilePath), out.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
            for (EventAttendee attendee : attendees) {
                out = "   - " + attendee.getEmail() +
                        (Boolean.TRUE.equals(attendee.getOptional()) ? " (Optional)" : "");
                System.out.println(out);
                Files.write(Paths.get(responseFilePath), "\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
                Files.write(Paths.get(responseFilePath), out.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
            }
        }
        out = "***";
        System.out.println(out);
        Files.write(Paths.get(responseFilePath), "\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        Files.write(Paths.get(responseFilePath), out.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    }
}
