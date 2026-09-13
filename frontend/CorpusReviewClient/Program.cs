using CorpusReviewClient;
using CorpusReviewClient.Services;
using Microsoft.AspNetCore.Components.Web;
using Microsoft.AspNetCore.Components.WebAssembly.Hosting;

var builder = WebAssemblyHostBuilder.CreateDefault(args);
builder.RootComponents.Add<App>("#app");
builder.RootComponents.Add<HeadOutlet>("head::after");

// ApiBaseUrl 为空时使用站点源地址（Docker 中由 nginx 将 /api 反代到后端）
var apiBase = builder.Configuration["ApiBaseUrl"];
builder.Services.AddScoped(_ => new HttpClient
{
    BaseAddress = new Uri(string.IsNullOrWhiteSpace(apiBase) ? builder.HostEnvironment.BaseAddress : apiBase)
});
builder.Services.AddScoped<ApiClient>();

await builder.Build().RunAsync();
