# 🚀 JGraphlet - The Tiny Powerhouse for Java Task Pipelines
[![Java 21](https://img.shields.io/badge/Java-21-blue.svg)](https://openjdk.org/projects/jdk/21/)
[![Maven 3](https://img.shields.io/badge/Maven-3-orange.svg)](https://maven.apache.org/)

> **Zero dependencies.** ✨

Welcome to **JGraphlet** - A simple, elegant task pipeline library, with zero dependencies! Whether you're building complex data processing workflows, orchestrating microservice calls, or just want to make your async tasks organized, JGraphlet has got your back! 

## 🎯 What Makes JGraphlet Awesome?

- **🪶 Lightweight**: Zero runtime dependencies!
- **⚡ Async-First**: Built for parallel execution, also support sync tasks.
- **🔗 Intuitive API**: Chain tasks naturally with fluent methods
- **🌊 Fan-In/Fan-Out**: Handle complex task dependencies with ease
- **💾 Smart Caching**: Optional task result caching for performance wins
- **🛡️ Rock Solid**: Comprehensive error handling and thread safety
- **🎨 Context**: Share context, or pass data <I, O> between tasks.

## 🚀 Quick Start

### Installation & Building

```bash
# Clone and build (requires Java 17+)
git clone https://github.com/sshaaf/jgraphlet.git
cd jgraphlet
mvn clean install

# Run tests to see it in action
mvn test
```

### Your First Pipeline

```java
import dev.shaaf.jgraphlet.Task;
import dev.shaaf.jgraphlet.TaskPipeline;

// Create a simple async task
Task<String, Integer> lengthTask = (input, context) ->
        CompletableFuture.supplyAsync(() -> input.length());

        Task<Integer, String> formatTask = (length, context) ->
                CompletableFuture.supplyAsync(() -> "Length: " + length);

        // Chain them together
        TaskPipeline pipeline = new TaskPipeline()
                .add("length", lengthTask)
                .then("format", formatTask);

        // Execute and get results!
        String result = (String) pipeline.run("Hello World!").join();
System.out.

        println(result); // "Length: 12"
```

**That's it!** You just built your first async pipeline! 🎉

## 📚 Core Concepts & Examples

### 🔧 Creating Tasks: Sync vs Async

#### Async Tasks (The Default Way)
Perfect for I/O operations, API calls, or CPU-intensive work:

```java
// Simulate an API call
Task<String, String> apiTask = (userId, context) -> 
    CompletableFuture.supplyAsync(() -> {
        // Simulate network delay
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return "User data for: " + userId;
    });

// Database query simulation
Task<String, Integer> countTask = (query, context) -> 
    CompletableFuture.supplyAsync(() -> {
        // Your database magic here
        return 42;
    });
```

#### Sync Tasks (For Simple Operations)
Perfect for quick transformations and business logic:

```java
// Simple string transformation
SyncTask<String, String> upperCaseTask = (input, context) -> 
    input.toUpperCase();

// Data validation
SyncTask<String, Boolean> validationTask = (email, context) -> {
    if (!email.contains("@")) {
        throw new TaskRunException("Invalid email format!");
    }
    return true;
};

// Mathematical operations
SyncTask<Integer, Integer> doubleTask = (number, context) -> 
    number * 2;
```

### 🔗 Building Pipelines: add() vs addTask() vs then()

Understanding these methods is key to pipeline mastery!

#### 🎯 `add()` - Start a Linear Chain
Use `add()` when you want to create a linear sequence and prepare for `then()`:

```java
TaskPipeline pipeline = new TaskPipeline()
    .add("validate", validationTask)      // Sets up the chain
    .then("process", processingTask)      // Links to validate
    .then("format", formattingTask);      // Links to process
```

#### 🏗️ `addTask()` - Add Independent Nodes
Use `addTask()` when building complex graphs with custom connections:

```java
TaskPipeline pipeline = new TaskPipeline()
    .addTask("input", inputTask)
    .addTask("taskA", taskA)
    .addTask("taskB", taskB)
    .addTask("merger", mergerTask);

// Now create custom connections
pipeline.connect("input", "taskA")
        .connect("input", "taskB")
        .connect("taskA", "merger")
        .connect("taskB", "merger");
```

#### ⛓️ `then()` - Linear Sequencing
Use `then()` after `add()` to create simple sequential chains:

```java
// This creates: fetch → transform → save
TaskPipeline pipeline = new TaskPipeline()
    .add("fetch", fetchDataTask)
    .then("transform", transformTask)
    .then("save", saveTask);
```

### 🌊 Fan-In Pipelines (Multiple Tasks → One Task)

One of JGraphlet's superpowers! Handle multiple inputs effortlessly:

```java
// Create tasks that run in parallel
SyncTask<String, String> fetchUserTask = (userId, context) -> 
    "User: " + userId;

SyncTask<String, String> fetchPrefsTask = (userId, context) -> 
    "Prefs: dark_mode=true";

// Fan-in task that receives results from multiple parents
Task<Map<String, Object>, String> combineTask = (inputs, context) -> 
    CompletableFuture.supplyAsync(() -> {
        String user = (String) inputs.get("fetchUser");
        String prefs = (String) inputs.get("fetchPrefs");
        return user + " | " + prefs;
    });

// Build the fan-in pipeline
TaskPipeline pipeline = new TaskPipeline()
    .addTask("fetchUser", fetchUserTask)
    .addTask("fetchPrefs", fetchPrefsTask)
    .addTask("combine", combineTask);

// Connect the fan-in
pipeline.connect("fetchUser", "combine")
        .connect("fetchPrefs", "combine");

// Execute - both fetch tasks run in parallel!
String result = (String) pipeline.run("user123").join();
```

### 🗂️ Using PipelineContext - Share Data Between Tasks

The `PipelineContext` is your shared workspace for passing data between tasks:

```java
// Producer task - stores data in context
Task<String, String> authTask = (token, context) -> 
    CompletableFuture.supplyAsync(() -> {
        String userId = authenticateToken(token);
        context.put("userId", userId);
        context.put("role", "admin");
        return "authenticated";
    });

// Consumer task - reads from context
Task<String, String> personalizeTask = (input, context) -> 
    CompletableFuture.supplyAsync(() -> {
        String userId = context.get("userId", String.class).orElse("anonymous");
        String role = context.get("role", String.class).orElse("guest");
        return "Welcome " + role + " " + userId + "!";
    });

TaskPipeline pipeline = new TaskPipeline()
    .add("auth", authTask)
    .then("personalize", personalizeTask);

String welcome = (String) pipeline.run("jwt-token-here").join();
```

## 🏆 Advanced Examples

### 🔄 Complex Workflow with Error Handling

```java
// Real-world example: User registration workflow
TaskPipeline userRegistration = new TaskPipeline()
    .add("validate", new SyncTask<UserData, UserData>() {
        public UserData executeSync(UserData user, PipelineContext context) {
            if (user.email() == null) throw new TaskRunException("Email required");
            context.put("validatedAt", System.currentTimeMillis());
            return user;
        }
    })
    .then("checkDuplicate", (userData, context) -> 
        CompletableFuture.supplyAsync(() -> {
            // Database check simulation
            boolean exists = checkUserExists(userData.email());
            if (exists) throw new TaskRunException("User already exists");
            return userData;
        }))
    .then("hashPassword", (userData, context) -> 
        CompletableFuture.supplyAsync(() -> {
            String hashed = hashPassword(userData.password());
            context.put("hashedPassword", hashed);
            return userData.withHashedPassword(hashed);
        }))
    .then("saveUser", (userData, context) -> 
        CompletableFuture.supplyAsync(() -> {
            return saveToDatabase(userData);
        }));

// Handle the registration
try {
    User newUser = (User) userRegistration.run(userData).join();
    System.out.println("User registered: " + newUser.id());
} catch (Exception e) {
    System.err.println("Registration failed: " + e.getMessage());
}
```

### 🎯 Caching for Performance

```java
// Enable caching for expensive operations
Task<String, String> expensiveApiCall = new Task<String, String>() {
    public CompletableFuture<String> execute(String input, PipelineContext context) {
        return CompletableFuture.supplyAsync(() -> {
            // Simulate expensive API call
            sleep(1000);
            return "Expensive result for: " + input;
        });
    }
    
    @Override
    public boolean isCacheable() {
        return true; // Enable caching!
    }
};

TaskPipeline pipeline = new TaskPipeline()
    .add("expensiveTask", expensiveApiCall);

// First call: takes 1 second
String result1 = (String) pipeline.run("same-input").join();

// Second call: instant! (cached)
String result2 = (String) pipeline.run("same-input").join();
```

### 🌟 Fan-Out + Fan-In Pattern

```java
// Process data through multiple parallel paths then combine
SyncTask<String, Integer> pathA = (data, ctx) -> data.length();
SyncTask<String, Integer> pathB = (data, ctx) -> data.hashCode();
SyncTask<String, Boolean> pathC = (data, ctx) -> data.contains("@");

Task<Map<String, Object>, String> finalMerger = (inputs, ctx) -> 
    CompletableFuture.supplyAsync(() -> {
        int length = (Integer) inputs.get("pathA");
        int hash = (Integer) inputs.get("pathB");
        boolean hasAt = (Boolean) inputs.get("pathC");
        
        return String.format("Analysis: length=%d, hash=%d, email=%s", 
                           length, hash, hasAt);
    });

TaskPipeline fanOutIn = new TaskPipeline()
    .addTask("pathA", pathA)
    .addTask("pathB", pathB)
    .addTask("pathC", pathC)
    .addTask("merger", finalMerger);

// All paths process the same input in parallel
fanOutIn.connect("pathA", "merger")
        .connect("pathB", "merger")
        .connect("pathC", "merger");
```

## 🛠️ Building & Integration

### Maven Integration

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>dev.jgraphlet</groupId>
    <artifactId>jgraphlet</artifactId>
    <version>0.1</version>
</dependency>
```

### Development Setup

```bash
# Requirements: Java 17+, Maven 3.6+
mvn clean compile        # Compile the library
mvn test                # Run comprehensive tests
mvn package             # Create JAR file
mvn install             # Install to local repository
```

## 🎨 Why You'll Love JGraphlet

- **🎯 Focus on Logic**: Spend time on your business logic, not plumbing
- **📈 Scales Naturally**: From simple chains to complex DAGs
- **🔧 Tool-Friendly**: Integrates seamlessly with Spring, Quarkus, or plain Java
- **🧪 Test-Friendly**: Easy to unit test individual tasks and whole pipelines
- **📖 Self-Documenting**: Your pipeline structure tells the story of your workflow

## 🚀 Get Started Today!

Ready to supercharge your Java applications? JGraphlet makes complex async workflows simple and fun!

```bash
git clone https://github.com/sshaaf/jgraphlet.git
cd jgraphlet
mvn clean install
```

Start building amazing pipelines in minutes, not hours! 

---

**Happy pipelining!** 🎉 Got questions? Open an issue - we love helping fellow developers succeed!

_Built with ❤️ for the Java community_
