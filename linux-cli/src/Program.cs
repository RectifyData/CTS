using System.Diagnostics;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Text.Json;
using CubicalCompare.Core.Project;
using CubicalCompare.Core.Renderer;
using SkiaSharp;
using Legacy = CubicalCompare.Windows;

internal static class Program
{
    private static string Version => typeof(Program).Assembly.GetCustomAttributes(typeof(System.Reflection.AssemblyInformationalVersionAttribute), false).OfType<System.Reflection.AssemblyInformationalVersionAttribute>().FirstOrDefault()?.InformationalVersion.Split('+')[0] ?? "unknown";
    private static readonly CancellationTokenSource Stop = new();
    private static CancellationToken Token => Stop.Token;
    private static readonly HashSet<string> Flags = ["force", "fast", "no-audio", "no-badges"];
    private static readonly HashSet<string> Values = ["output", "renderer", "width", "height", "fps", "duration", "frame", "start", "frames", "assets-root", "font", "audio", "encoder", "preset", "crf", "ffmpeg"];
    private static async Task<int> Main(string[] args)
    {
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; Stop.Cancel(); };
        try
        {
            if (args.Length == 0 || args.Contains("--help") || args[0] is "help" or "-h") { Help(); return 0; }
            if (args[0] == "--version") { Console.WriteLine(Version); return 0; }
            var a = new Args(args.Skip(1).ToArray());
            switch (args[0])
            {
                case "doctor": a.Check(0, "ffmpeg"); await Doctor(a); break;
                case "self-test": a.Check(0); Legacy.RendererExportRegressionChecks.Run(); Console.WriteLine("Renderer regressions passed."); break;
                case "import": a.Check(1, "output", "force", "width", "height", "fps", "duration", "assets-root", "font", "no-badges"); await Import(a); break;
                case "inspect": a.Check(1, "renderer", "assets-root", "font", "width", "height", "fps", "duration", "no-badges"); await Inspect(a); break;
                case "renderer-info": a.Check(1); RendererInfo(a.Input); break;
                case "preview": a.Check(1, "output", "renderer", "frame", "width", "height", "fps", "duration", "assets-root", "font", "force", "no-badges"); await Preview(a); break;
                case "render": a.Check(1, "output", "renderer", "start", "frames", "width", "height", "fps", "duration", "assets-root", "font", "audio", "no-audio", "encoder", "preset", "crf", "ffmpeg", "force", "fast", "no-badges"); await Render(a); break;
                default: throw new ArgumentException($"Unknown command '{args[0]}'. Use --help.");
            }
            return 0;
        }
        catch (OperationCanceledException) { Console.Error.WriteLine("Cancelled; incomplete output removed."); return 130; }
        catch (Exception e) { Console.Error.WriteLine($"Error: {e.Message}"); return 1; }
    }
    private static void Help() => Console.WriteLine("""
Cubical Compare CLI — Debian
Usage:
  cubical-compare import cards.csv -o project.ccproject
  cubical-compare inspect project.ccproject [--renderer model.renderer3]
  cubical-compare renderer-info model.renderer3
  cubical-compare preview project.ccproject --renderer model.renderer3 --frame 240 -o frame.png
  cubical-compare render project.ccproject --renderer model.renderer3 -o video.mp4
  cubical-compare doctor | self-test | --version
Options:
  --width N --height N --fps N --duration SEC
  --assets-root DIR --font FILE --start N --frames N
  --audio FILE --no-audio --no-badges --fast --force
  --encoder libx264|libx265|h264_nvenc|h264_qsv|h264_v4l2m2m
  --preset NAME --crf N --ffmpeg PATH
Without --renderer, use the original built-in native-standard renderer (API 1).
""");
    private static async Task Doctor(Args a)
    {
        using var b = new SKBitmap(8, 8); b.Erase(SKColors.Red);
        if (b.GetPixel(0, 0) != SKColors.Red) throw new Exception("SkiaSharp pixel test failed.");
        Console.WriteLine($"SkiaSharp OK; {RuntimeInformation.OSDescription}; {RuntimeInformation.ProcessArchitecture}");
        await Run(a.Get("ffmpeg", "ffmpeg"), ["-version"]);
    }
    private static async Task Import(Args a)
    {
        string output = Output(a, ".ccproject");
        var result = await CsvImportService.ImportAsync(a.Input, Token);
        foreach (var w in result.Warnings) Console.Error.WriteLine("Warning: " + w);
        var p = new ComparisonProject { Name = Path.GetFileNameWithoutExtension(a.Input), Cards = result.Cards };
        if (result.DurationSeconds is double d) { p.AutoLength = false; p.CustomLengthSeconds = d; }
        Apply(p, a);
        foreach (var c in p.Cards)
            if (!string.IsNullOrWhiteSpace(c.ImagePath) && !WebImageSource.IsRemoteSource(c.ImagePath))
                c.ImagePath = Resolve(c.ImagePath, a.Input, a);
        var temp = Stage(output);
        try { await ProjectFileService.SaveAsync(p, temp, Token); File.Move(temp, output, a.Has("force")); }
        finally { Delete(temp); }
        Console.WriteLine($"Imported {p.Cards.Count} cards: {output}");
    }
    private static void Apply(ComparisonProject p, Args a)
    {
        p.Width = a.Int("width", p.Width, 320, 3840);
        p.Height = a.Int("height", p.Height, 240, 2160);
        p.Fps = a.Int("fps", p.Fps, 1, 240);
        if (a.Has("duration")) { p.CustomLengthSeconds = a.Double("duration", 1, 0.01, 86400); p.AutoLength = false; }
        if (a.Has("font")) { p.RenderFontFile = Path.GetFullPath(a.Get("font")); if (!File.Exists(p.RenderFontFile)) throw new FileNotFoundException("Font file not found: " + p.RenderFontFile); }
        if (a.Has("no-badges")) p.ShowBadges = false;
        ProjectFileService.ValidateAndNormalize(p);
    }
    private static string Resolve(string path, string input, Args a)
    {
        if (string.IsNullOrWhiteSpace(path)) return "";
        if (WebImageSource.IsRemoteSource(path)) return path;
        if (Uri.TryCreate(path, UriKind.Absolute, out var uri) && uri.IsFile && !path.Contains('\\')) path = uri.LocalPath;
        string normal = path.Replace('\\', '/');
        var directory = Path.GetDirectoryName(Path.GetFullPath(input))!;
        string candidate = Path.GetFullPath(normal, directory);
        if (File.Exists(candidate)) return candidate;
        if (a.Has("assets-root"))
        {
            string direct = Path.Combine(Path.GetFullPath(a.Get("assets-root")), Path.GetFileName(normal));
            if (File.Exists(direct)) return direct;
        }
        throw new FileNotFoundException($"Asset missing: {path}. Copy it to Debian or use --assets-root DIR.");
    }
    private static async Task<ComparisonProject> Load(Args a, bool resolveAudio = false)
    {
        var p = await ProjectFileService.LoadAsync(a.Input, Token);
        Apply(p, a);
        if (!string.IsNullOrWhiteSpace(p.RenderFontFile)) p.RenderFontFile = Resolve(p.RenderFontFile, a.Input, a);
        RenderFontSelection.ApplyProjectFont(p.RenderFontFamily, p.RenderFontFile);
        foreach (var c in p.Cards)
        {
            Token.ThrowIfCancellationRequested();
            if (string.IsNullOrWhiteSpace(c.ImagePath)) continue;
            c.ImagePath = Resolve(c.ImagePath, a.Input, a);
            if (WebImageSource.IsRemoteSource(c.ImagePath))
                c.ImagePath = await WebImageSource.ResolveToLocalFileAsync(c.ImagePath, Token) ?? throw new IOException($"Cannot download artwork for {c.Title}");
            using var image = SKBitmap.Decode(c.ImagePath);
            if (image is null) throw new InvalidDataException($"Invalid artwork: {c.ImagePath}");
        }
        if (resolveAudio && !a.Has("no-audio") && !a.Has("audio"))
            p.SoundtrackPaths = p.SoundtrackPaths.Select(x => Resolve(x, a.Input, a)).ToList();
        return p;
    }
    private static LegacyRendererAdapter Renderer(Args a)
    {
        if (!a.Has("renderer")) return LegacyRendererAdapter.CreateBuiltIn();
        var path = a.Get("renderer");
        if (new FileInfo(path).Length > 128L * 1024 * 1024) throw new InvalidDataException("Renderer exceeds 128 MiB.");
        return LegacyRendererAdapter.Load(path);
    }
    private static void RendererInfo(string input)
    {
        if (new FileInfo(input).Length > 128L * 1024 * 1024) throw new InvalidDataException("Renderer exceeds 128 MiB.");
        var r = Legacy.RendererBundleReader.Inspect(input);
        Console.WriteLine(JsonSerializer.Serialize(new { r.Spec.Id, r.Spec.Name, r.Spec.RendererApi, r.Spec.Engine, r.Spec.ReferenceFps, r.Report.Compatible, r.Report.Errors, r.Report.Warnings }, new JsonSerializerOptions { WriteIndented = true }));
        if (!r.Report.Compatible) throw new InvalidDataException("Renderer is incompatible.");
    }
    private static async Task Inspect(Args a)
    {
        var p = await Load(a); using var r = Renderer(a);
        var n = r.FrameCount(p);
        Console.WriteLine(JsonSerializer.Serialize(new { p.Name, p.Width, p.Height, p.Fps, Cards = p.Cards.Count, Frames = n, Seconds = n / (double)p.Fps, Renderer = r.Name, r.Api, r.ReferenceFps }, new JsonSerializerOptions { WriteIndented = true }));
    }
    private static async Task Preview(Args a)
    {
        var output = Output(a, ".png"); var p = await Load(a); using var r = Renderer(a);
        var n = r.FrameCount(p); var f = a.Int("frame", r.InitialPreviewFrame(p), 0, n - 1);
        var temp = Stage(output);
        try { await File.WriteAllBytesAsync(temp, r.RenderPng(p, f), Token); File.Move(temp, output, a.Has("force")); }
        finally { Delete(temp); }
        Console.WriteLine($"Frame {f}: {output}");
    }
    private static async Task Render(Args a)
    {
        if (a.Has("audio") && a.Has("no-audio")) throw new ArgumentException("Choose --audio or --no-audio, not both.");
        var output = Output(a, ".mp4"); var p = await Load(a, true); using var r = Renderer(a);
        if ((p.Width & 1) != 0 || (p.Height & 1) != 0) throw new ArgumentException("MP4 requires even width and height.");
        var total = r.FrameCount(p); var start = a.Int("start", 0, 0, total - 1);
        var count = a.Int("frames", total - start, 1, total - start);
        if (p.Fps != r.ReferenceFps) Console.Error.WriteLine($"Warning: project FPS {p.Fps} differs from renderer reference FPS {r.ReferenceFps}; using Windows frame-index semantics.");
        string encoder = a.Get("encoder", "libx264");
        if (!new[] { "libx264", "libx265", "h264_nvenc", "h264_qsv", "h264_v4l2m2m" }.Contains(encoder)) throw new ArgumentException("Unsupported encoder.");
        bool software = encoder is "libx264" or "libx265";
        if (!software && (a.Has("crf") || a.Has("preset"))) throw new ArgumentException("--crf and --preset are for software encoders only.");
        string ff = a.Get("ffmpeg", "ffmpeg"), stage = Stage(output);
        string work = Path.Combine(Path.GetTempPath(), "cc-cli-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(work);
        try
        {
            var audio = await PrepareAudio(a, p, r, ff, work);
            var cmd = new List<string> { "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-f", "rawvideo", "-pixel_format", "bgra", "-video_size", $"{p.Width}x{p.Height}", "-framerate", p.Fps.ToString(), "-i", "pipe:0" };
            if (audio.Path is not null)
            {
                if (audio.Loop) cmd.AddRange(["-stream_loop", "-1"]);
                cmd.AddRange(["-i", audio.Path, "-map", "0:v:0", "-map", "1:a:0"]);
                double duration = count / (double)p.Fps, offset = start / (double)p.Fps;
                string filter = $"atrim=start={Num(offset)},asetpts=PTS-STARTPTS,volume={Num(audio.Volume)},apad";
                if (audio.Fade) { double fade = Math.Min(2, duration); filter += $",afade=t=out:st={Num(duration-fade)}:d={Num(fade)}"; }
                cmd.AddRange(["-af", filter, "-c:a", "aac", "-b:a", "192k", "-t", Num(duration)]);
            }
            else cmd.AddRange(["-map", "0:v:0", "-an"]);
            cmd.AddRange(["-c:v", encoder, "-pix_fmt", "yuv420p"]);
            if (software) cmd.AddRange(["-preset", a.Get("preset", "medium"), "-crf", a.Int("crf", 18, 0, 51).ToString()]);
            cmd.AddRange(["-movflags", "+faststart", "-f", "mp4", stage]);
            r.SetFastExportSampling(a.Has("fast")); var session = r.CreateRenderSession(p);
            byte[] buffer = new byte[checked(p.Width * p.Height * 4)]; var timer = Stopwatch.StartNew();
            Console.Error.WriteLine($"Rendering {count} frames at {p.Width}x{p.Height}, {p.Fps} fps ({encoder})");
            await Run(ff, cmd, async stream =>
            {
                for (int i = 0; i < count; i++)
                {
                    Token.ThrowIfCancellationRequested();
                    using var frame = session.Render(start + i, p.Width, p.Height);
                    CopyFrame(frame, buffer, p.Width, p.Height);
                    await stream.WriteAsync(buffer, Token);
                    if (i == 0 || (i + 1) % Math.Max(1, p.Fps) == 0 || i + 1 == count)
                        Console.Error.WriteLine($"{i+1}/{count} ({100.0*(i+1)/count:F1}%) | {(i+1)/Math.Max(.001,timer.Elapsed.TotalSeconds):F1} frames/s");
                }
            });
            if (new FileInfo(stage).Length == 0) throw new IOException("FFmpeg produced no output.");
            Token.ThrowIfCancellationRequested(); File.Move(stage, output, a.Has("force")); Console.WriteLine(output);
        }
        finally { Delete(stage); try { Directory.Delete(work, true); } catch { } }
    }
    private sealed record Audio(string? Path, double Volume, bool Loop, bool Fade);
    private static async Task<Audio> PrepareAudio(Args a, ComparisonProject p, LegacyRendererAdapter r, string ff, string work)
    {
        if (a.Has("no-audio")) return new(null, 1, false, false);
        var tracks = a.Has("audio") ? new List<string> { Path.GetFullPath(a.Get("audio")) } : p.SoundtrackPaths.ToList();
        double volume = p.SoundtrackVolume; bool loop = p.SoundtrackLoop;
        if (tracks.Count == 0 && r.EmbeddedAudio is { } embedded)
        {
            string f = Path.Combine(work, "embedded" + Path.GetExtension(embedded.FileName));
            await File.WriteAllBytesAsync(f, embedded.Data, Token); tracks.Add(f); volume = embedded.Volume; loop = embedded.Loop;
        }
        if (tracks.Count == 0) return new(null, 1, false, false);
        foreach (var track in tracks) if (!File.Exists(track)) throw new FileNotFoundException($"Soundtrack missing: {track}");
        if (tracks.Count == 1) return new(tracks[0], volume, loop, p.SoundtrackFadeOut);
        string playlist = Path.Combine(work, "playlist.mka");
        var cmd = new List<string> { "-hide_banner", "-loglevel", "error", "-nostdin", "-y" };
        foreach (var track in tracks) cmd.AddRange(["-i", track]);
        string graph = string.Join(';', tracks.Select((_,i) => $"[{i}:a:0]aresample=48000,aformat=sample_fmts=s16:channel_layouts=stereo,asetpts=PTS-STARTPTS[a{i}]")) + ";" + string.Concat(tracks.Select((_,i) => $"[a{i}]")) + $"concat=n={tracks.Count}:v=0:a=1[out]";
        cmd.AddRange(["-filter_complex", graph, "-map", "[out]", "-c:a", "flac", playlist]); await Run(ff, cmd);
        return new(playlist, volume, loop, p.SoundtrackFadeOut);
    }
    private static void CopyFrame(SKBitmap frame, byte[] buffer, int width, int height)
    {
        if (frame.Width != width || frame.Height != height) throw new InvalidDataException("Unexpected frame dimensions.");
        if (frame.ColorType != SKColorType.Bgra8888)
        {
            using var converted = new SKBitmap(new SKImageInfo(width, height, SKColorType.Bgra8888, SKAlphaType.Premul));
            using (var canvas = new SKCanvas(converted)) { canvas.Clear(SKColors.Black); canvas.DrawBitmap(frame, 0, 0); canvas.Flush(); }
            CopyFrame(converted, buffer, width, height); return;
        }
        var ptr = frame.GetPixels(); int stride = checked(width * 4);
        if (ptr == IntPtr.Zero || frame.RowBytes < stride) throw new InvalidDataException("Invalid pixel buffer.");
        for (int y = 0; y < height; y++) Marshal.Copy(IntPtr.Add(ptr, checked(y * frame.RowBytes)), buffer, y * stride, stride);
    }
    private static async Task Run(string file, IEnumerable<string> args, Func<Stream, Task>? feed = null)
    {
        var info = new ProcessStartInfo(file) { UseShellExecute = false, RedirectStandardInput = feed is not null };
        foreach (var arg in args) info.ArgumentList.Add(arg);
        using var process = Process.Start(info) ?? throw new IOException($"Could not start {file}");
        using var cancel = Token.Register(() => { try { if (!process.HasExited) process.Kill(true); } catch { } });
        try
        {
            if (feed is not null) { await feed(process.StandardInput.BaseStream); process.StandardInput.Close(); }
            await process.WaitForExitAsync(Token); Token.ThrowIfCancellationRequested();
            if (process.ExitCode != 0) throw new IOException($"{file} exited with code {process.ExitCode}");
        }
        catch { try { if (!process.HasExited) process.Kill(true); } catch { } Token.ThrowIfCancellationRequested(); throw; }
    }
    private static string Num(double n) => n.ToString("0.#########", CultureInfo.InvariantCulture);
    private static string Output(Args a, string extension)
    {
        string p = Path.GetFullPath(a.Get("output"));
        if (!p.EndsWith(extension, StringComparison.OrdinalIgnoreCase)) throw new ArgumentException($"Output must end in {extension}");
        if (File.Exists(p) && !a.Has("force")) throw new IOException($"Output exists: {p}. Use --force to overwrite.");
        if (p == Path.GetFullPath(a.Input)) throw new IOException("Input and output must differ.");
        if (a.Has("renderer") && p == Path.GetFullPath(a.Get("renderer"))) throw new IOException("Renderer and output must differ.");
        if (a.Has("audio") && p == Path.GetFullPath(a.Get("audio"))) throw new IOException("Audio and output must differ.");
        Directory.CreateDirectory(Path.GetDirectoryName(p)!); return p;
    }
    private static string Stage(string path) => path + ".partial-" + Guid.NewGuid().ToString("N");
    private static void Delete(string path) { try { File.Delete(path); } catch { } }
    private sealed class Args
    {
        private readonly Dictionary<string, string> options = new(); private readonly List<string> positional = [];
        public string Input => positional[0];
        public Args(string[] args)
        {
            for (int i = 0; i < args.Length; i++)
            {
                var s = args[i]; if (s == "--") { positional.AddRange(args.Skip(i+1)); break; }
                if (s == "-o") s = "--output";
                if (!s.StartsWith('-')) { positional.Add(s); continue; }
                if (!s.StartsWith("--")) throw new ArgumentException($"Unknown option {s}");
                string k = s[2..]; if (options.ContainsKey(k)) throw new ArgumentException($"Duplicate option {s}");
                if (Flags.Contains(k)) options.Add(k, "true");
                else if (Values.Contains(k) && i + 1 < args.Length && !args[i+1].StartsWith("--")) options.Add(k, args[++i]);
                else throw new ArgumentException($"Unknown option or missing value: {s}");
            }
        }
        public bool Has(string k) => options.ContainsKey(k);
        public string Get(string k, string? fallback = null) => options.TryGetValue(k, out var v) ? v : fallback ?? throw new ArgumentException($"--{k} is required.");
        public int Int(string k, int fallback, int min, int max) => int.TryParse(Get(k, fallback.ToString(CultureInfo.InvariantCulture)), NumberStyles.Integer, CultureInfo.InvariantCulture, out var v) && v >= min && v <= max ? v : throw new ArgumentException($"--{k} must be between {min} and {max}.");
        public double Double(string k, double fallback, double min, double max) => double.TryParse(Get(k, Num(fallback)), NumberStyles.Float, CultureInfo.InvariantCulture, out var v) && double.IsFinite(v) && v >= min && v <= max ? v : throw new ArgumentException($"--{k} must be between {min} and {max}.");
        public void Check(int inputs, params string[] allowed)
        {
            if (positional.Count != inputs) throw new ArgumentException($"Expected {inputs} input path(s). Use --help.");
            foreach (var k in options.Keys) if (!allowed.Contains(k)) throw new ArgumentException($"--{k} is not valid for this command.");
        }
    }
}
