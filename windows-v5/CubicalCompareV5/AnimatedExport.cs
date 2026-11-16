using Microsoft.Win32;
using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Windows;

namespace CubicalCompareV5;

public partial class MainWindow
{
    // The standalone CLI is shipped next to the WPF application by the
    // three-platform build. Unlike the original two-seconds-per-card static
    // export path, this renders an animated four-column video timeline.
    private async void AnimatedExport_Click(object sender, RoutedEventArgs e)
    {
        if (_exporting) return;
        string executable = Path.Combine(AppContext.BaseDirectory, "ccrender.exe");
        if (!File.Exists(executable))
        {
            System.Windows.MessageBox.Show(this,
                "The animated video engine (ccrender.exe) was not found. Install the full V5 package or copy ccrender.exe next to CubicalCompareV5.exe. The legacy static export remains available in Project.",
                "Animated engine missing", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        PersistInk();
        var dialog = new Microsoft.Win32.SaveFileDialog
        {
            Filter = "Animated MP4 video|*.mp4",
            DefaultExt = ".mp4",
            AddExtension = true,
            FileName = "CubicalCompare-Animated.mp4"
        };
        if (dialog.ShowDialog(this) != true) return;

        string input = Path.Combine(Path.GetTempPath(), "CubicalCompare-" + Guid.NewGuid().ToString("N") + ".ccv5");
        _exporting = true;
        ExportButton.IsEnabled = false;
        AnimatedExportButton.IsEnabled = false;
        NavExportButton.IsEnabled = false;
        ExportProgress.Visibility = Visibility.Visible;
        ExportProgress.IsIndeterminate = true;
        StatusLabel.Text = "Rendering the animated comparison timeline...";
        try
        {
            File.WriteAllText(input, JsonSerializer.Serialize(BuildProjectSnapshot(), _json), new UTF8Encoding(false));
            var start = new ProcessStartInfo
            {
                FileName = executable,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardError = true,
                RedirectStandardOutput = true,
                WorkingDirectory = Path.GetDirectoryName(input) ?? Environment.CurrentDirectory
            };
            start.ArgumentList.Add("render");
            start.ArgumentList.Add(input);
            start.ArgumentList.Add("--output");
            start.ArgumentList.Add(dialog.FileName);
            start.ArgumentList.Add("--width");
            start.ArgumentList.Add("1920");
            start.ArgumentList.Add("--height");
            start.ArgumentList.Add("1080");
            start.ArgumentList.Add("--fps");
            start.ArgumentList.Add("30");

            using var process = new Process { StartInfo = start };
            if (!process.Start()) throw new IOException("Could not start the animated renderer.");
            var errorTask = process.StandardError.ReadToEndAsync();
            var outputTask = process.StandardOutput.ReadToEndAsync();
            await process.WaitForExitAsync();
            var errors = await errorTask;
            await outputTask;
            if (process.ExitCode != 0)
                throw new IOException("Animated export failed: " + errors.Trim());
            if (!File.Exists(dialog.FileName) || new FileInfo(dialog.FileName).Length == 0)
                throw new IOException("The animation engine returned no video.");
            StatusLabel.Text = "Animated MP4 exported: " + Path.GetFileName(dialog.FileName);
        }
        catch (Exception ex)
        {
            ShowError(ex);
        }
        finally
        {
            try { File.Delete(input); } catch (IOException) { } catch (UnauthorizedAccessException) { }
            ExportProgress.IsIndeterminate = false;
            ExportProgress.Visibility = Visibility.Collapsed;
            ExportButton.IsEnabled = true;
            AnimatedExportButton.IsEnabled = true;
            NavExportButton.IsEnabled = true;
            _exporting = false;
        }
    }
}
