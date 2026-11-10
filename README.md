# Cross-platform app

One app, two API implementations side by side (Java Spring Boot and .NET), PostgreSQL, and a React Native UI.

## Layout

```
app/
  api/
    java/           Spring Boot API (Java 21, Maven)
    dotnet/         ASP.NET Core API (.NET 10)
  ui/
    react-native/   React Native app
deployment/
  local/            Docker Compose for local runs
  azure/            Azure pipelines and infrastructure (later)
```

## Run locally

```bash
docker compose -f deployment/local/docker-compose.yml up --build
```

| Service    | URL                              |
|------------|----------------------------------|
| Java API   | http://localhost:8080/api/health |
| .NET API   | http://localhost:8081/api/health |
| PostgreSQL | localhost:5432 (user/password `app`/`app`) |

Each API has its own database (`app_java`, `app_dotnet`) on the same PostgreSQL instance,
so each stack owns its schema and migrations. The databases are created by
`deployment/local/postgres/init.sql` the first time the data volume is created.

Both APIs expose the same contract, so the UI can switch between them by changing its base URL.
