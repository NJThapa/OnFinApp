using Npgsql;
using OnFinApp.Api.Analyses;
using OnFinApp.Api.Auth;
using OnFinApp.Api.Common;
using OnFinApp.Api.Health;
using OnFinApp.Api.Instruments;
using OnFinApp.Api.Llm;
using OnFinApp.Api.Prices;
using OnFinApp.Api.Users;
using OnFinApp.Api.Watchlist;

var builder = WebApplication.CreateBuilder(args);
var config = builder.Configuration;

builder.AddStructuredLogging();
builder.AddTelemetry();

var connectionString = config.GetConnectionString("Default")
    ?? "Host=localhost;Port=5432;Database=onfinapp;Username=app;Password=app";
builder.Services.AddSingleton(NpgsqlDataSource.Create(connectionString));
builder.Services.AddSingleton(TimeProvider.System);

builder.Services.AddOidcResourceServer(config);
builder.Services.AddApiCors(config);
builder.Services.AddApiProblemDetails();
builder.Services.AddValidation();

builder.Services.AddUsers();
builder.Services.AddPrices(config);
builder.Services.AddInstruments();
builder.Services.AddWatchlist(config);
builder.Services.AddLlm(config);
builder.Services.AddAnalyses(config);

var app = builder.Build();

app.UseMiddleware<CorrelationMiddleware>();
app.UseExceptionHandler();
app.UseStatusCodePages();
app.UseCors();
app.UseAuthentication();
app.UseAuthorization();

app.MapHealth();

var v1 = app.MapGroup("/api/v1").RequireAuthorization();
v1.MapUsers();
v1.MapInstruments();
v1.MapWatchlist();
v1.MapAnalyses();

app.Run();
