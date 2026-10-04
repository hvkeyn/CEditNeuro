# Extended display over the adb link that is already open.
# Uses the virtual display device Windows already has. Does not download a program.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class AdbMon {
  public const int CDS_UPDATEREGISTRY = 0x1;
  public const int CDS_NORESET = unchecked((int)0x10000000);
  public const int DM_POSITION = 0x20;
  public const int DM_BITSPERPEL = 0x40000;
  public const int DM_PELSWIDTH = 0x80000;
  public const int DM_PELSHEIGHT = 0x100000;
  public const int DM_DISPLAYFREQUENCY = 0x400000;
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Auto)]
  public struct DEVMODE {
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string dmDeviceName;
    public short dmSpecVersion; public short dmDriverVersion; public short dmSize; public short dmDriverExtra;
    public int dmFields; public int dmPositionX; public int dmPositionY; public int dmDisplayOrientation; public int dmDisplayFixedOutput;
    public short dmColor; public short dmDuplex; public short dmYResolution; public short dmTTOption; public short dmCollate;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string dmFormName;
    public short dmLogPixels; public int dmBitsPerPel; public int dmPelsWidth; public int dmPelsHeight; public int dmDisplayFlags; public int dmDisplayFrequency;
  }
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Auto)]
  public struct DISPLAY_DEVICE {
    public int cb;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string DeviceName;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string DeviceString;
    public int StateFlags;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string DeviceID;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string DeviceKey;
  }
  [StructLayout(LayoutKind.Sequential)]
  public struct MOUSEINPUT {
    public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr extra;
  }
  [StructLayout(LayoutKind.Sequential)]
  public struct INPUT {
    public int type; public MOUSEINPUT mi;
  }
  [DllImport("user32.dll", CharSet=CharSet.Auto)] public static extern bool EnumDisplayDevices(string device, uint num, ref DISPLAY_DEVICE dd, uint flags);
  [DllImport("user32.dll", CharSet=CharSet.Auto)] public static extern int ChangeDisplaySettingsEx(string device, ref DEVMODE dm, IntPtr hwnd, int flags, IntPtr param);
  [DllImport("user32.dll", CharSet=CharSet.Auto)] public static extern int ChangeDisplaySettingsEx(string device, IntPtr dm, IntPtr hwnd, int flags, IntPtr param);
  [DllImport("user32.dll", SetLastError=true)] public static extern uint SendInput(uint n, INPUT[] inputs, int cb);
  public static string VirtualName() {
    for (uint i = 0; i < 16; i++) {
      var dd = new DISPLAY_DEVICE();
      dd.cb = Marshal.SizeOf(dd);
      if (!EnumDisplayDevices(null, i, ref dd, 0)) break;
      if (dd.DeviceString == "Virtual Display Driver") return dd.DeviceName;
    }
    return "";
  }
  public static int Attached(string name) {
    for (uint i = 0; i < 16; i++) {
      var dd = new DISPLAY_DEVICE();
      dd.cb = Marshal.SizeOf(dd);
      if (!EnumDisplayDevices(null, i, ref dd, 0)) break;
      if (dd.DeviceName == name) return (dd.StateFlags & 1);
    }
    return 0;
  }
  public static int Extend(string name, int x) {
    var dm = new DEVMODE();
    dm.dmSize = (short)Marshal.SizeOf(typeof(DEVMODE));
    dm.dmPelsWidth = 1920; dm.dmPelsHeight = 1080; dm.dmBitsPerPel = 32; dm.dmDisplayFrequency = 60;
    dm.dmPositionX = x; dm.dmPositionY = 0;
    dm.dmFields = DM_POSITION | DM_PELSWIDTH | DM_PELSHEIGHT | DM_BITSPERPEL | DM_DISPLAYFREQUENCY;
    int set = ChangeDisplaySettingsEx(name, ref dm, IntPtr.Zero, CDS_UPDATEREGISTRY | CDS_NORESET, IntPtr.Zero);
    int apply = ChangeDisplaySettingsEx(null, IntPtr.Zero, IntPtr.Zero, 0, IntPtr.Zero);
    return set == 0 && apply == 0 ? 0 : set;
  }
  public static void Pointer(int x, int y, int kind, int left, int top, int width, int height) {
    if (width < 2 || height < 2) return;
    uint flags = 0x0001 | 0x8000 | 0x4000;
    if (kind == 1) flags |= 0x0002;
    if (kind == 2) flags |= 0x0004;
    var input = new INPUT();
    input.type = 0;
    input.mi.dx = (x - left) * 65535 / (width - 1);
    input.mi.dy = (y - top) * 65535 / (height - 1);
    input.mi.dwFlags = flags;
    SendInput(1, new INPUT[] { input }, Marshal.SizeOf(typeof(INPUT)));
  }
}
'@

function Right-Edge([string]$skip) {
  $edge = 0
  foreach ($screen in [System.Windows.Forms.Screen]::AllScreens) {
    if ($screen.DeviceName -eq $skip) { continue }
    $end = $screen.Bounds.X + $screen.Bounds.Width
    if ($end -gt $edge) { $edge = $end }
  }
  return $edge
}

$name = [AdbMon]::VirtualName()
if (-not $name) {
  Write-Output 'This computer has no virtual display device, so an extra monitor was not added.'
  exit 2
}
if ([AdbMon]::Attached($name) -eq 0) {
  $edge = Right-Edge $name
  $code = [AdbMon]::Extend($name, $edge)
  if ($code -ne 0) {
    Write-Output "The virtual display did not join the desktop (code $code)."
    exit 2
  }
  Start-Sleep -Seconds 2
}
$screen = [System.Windows.Forms.Screen]::AllScreens | Where-Object { $_.DeviceName -eq $name } | Select-Object -First 1
if (-not $screen) {
  Write-Output 'The virtual display is installed but it is not on the desktop.'
  exit 2
}
$bounds = $screen.Bounds
$virtual = [System.Windows.Forms.SystemInformation]::VirtualScreen
Write-Output ("display " + $name + " " + $bounds.Width + "x" + $bounds.Height + " at " + $bounds.X + "," + $bounds.Y)

$serial = $null
adb devices | ForEach-Object {
  $line = $_.Trim()
  if ($line -match '^(?<id>.+?)\s+device$') { $script:serial = $Matches['id'].Trim() }
}
if (-not $serial) {
  Write-Output 'No adb device is connected. The extra display is on the desktop, but the phone has no tunnel.'
  exit 2
}
adb -s $serial reverse tcp:8791 tcp:8791 | Out-Null
Write-Output 'tunnel tcp:8791'

$page = @'
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden;touch-action:none}img{width:100%;height:100%;object-fit:contain}</style>
</head><body><img id="v" alt=""><script>
var img=document.getElementById("v");
var W=__W__, H=__H__;
function tick(){img.src="/frame.jpg?t="+Date.now()}
img.onload=function(){setTimeout(tick,160)}; img.onerror=function(){setTimeout(tick,400)}; tick();
function point(ev){
  var r=img.getBoundingClientRect();
  var nw=img.naturalWidth||W, nh=img.naturalHeight||H;
  var scale=Math.min(r.width/nw, r.height/nh); if(scale<=0) return null;
  var dw=nw*scale, dh=nh*scale;
  var x=Math.round((ev.clientX-(r.left+(r.width-dw)/2))/scale);
  var y=Math.round((ev.clientY-(r.top+(r.height-dh)/2))/scale);
  if(x<0||y<0||x>=nw||y>=nh) return null;
  return {x:x,y:y};
}
function send(ev, kind){
  var p=point(ev); if(!p) return; p.kind=kind;
  fetch("/input",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(p)});
}
img.addEventListener("pointerdown",function(ev){img.setPointerCapture(ev.pointerId);send(ev,"down");ev.preventDefault()});
img.addEventListener("pointermove",function(ev){send(ev,"move");ev.preventDefault()});
img.addEventListener("pointerup",function(ev){send(ev,"up");ev.preventDefault()});
img.addEventListener("pointercancel",function(ev){send(ev,"up")});
</script></body></html>
'@
$page = $page.Replace('__W__', [string]$bounds.Width).Replace('__H__', [string]$bounds.Height)
$codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' } | Select-Object -First 1
$quality = New-Object System.Drawing.Imaging.EncoderParameters 1
$quality.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter ([System.Drawing.Imaging.Encoder]::Quality, [int64]55)
$gate = New-Object System.Object
$held = $false

function Grab-Frame {
  $bmp = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.CopyFromScreen($bounds.X, $bounds.Y, 0, 0, $bmp.Size)
  $g.Dispose()
  $ms = New-Object System.IO.MemoryStream
  $bmp.Save($ms, $codec, $quality)
  $bmp.Dispose()
  return $ms.ToArray()
}

$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add('http://127.0.0.1:8791/')
$listener.Start()
Write-Output 'address: http://127.0.0.1:8791/'
adb -s $serial shell am start -n com.hvkeyn.ceditneuro.debug/com.hvkeyn.ceditneuro.MainActivity --es url http://127.0.0.1:8791/ | Out-Null
try {
  while ($listener.IsListening) {
    $ctx = $listener.GetContext()
    $req = $ctx.Request
    $res = $ctx.Response
    try {
      $path = $req.Url.AbsolutePath
      if ($req.HttpMethod -eq 'GET' -and ($path -eq '/' -or $path -eq '')) {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($page)
        $res.ContentType = 'text/html; charset=utf-8'
        $res.ContentLength64 = $bytes.Length
        $res.OutputStream.Write($bytes, 0, $bytes.Length)
      } elseif ($req.HttpMethod -eq 'GET' -and $path -eq '/frame.jpg') {
        $jpeg = $null
        [System.Threading.Monitor]::Enter($gate)
        try { $jpeg = Grab-Frame } finally { [System.Threading.Monitor]::Exit($gate) }
        $res.ContentType = 'image/jpeg'
        $res.Headers['Cache-Control'] = 'no-store'
        $res.ContentLength64 = $jpeg.Length
        $res.OutputStream.Write($jpeg, 0, $jpeg.Length)
      } elseif ($req.HttpMethod -eq 'POST' -and $path -eq '/input') {
        $reader = New-Object System.IO.StreamReader($req.InputStream, $req.ContentEncoding)
        $raw = $reader.ReadToEnd()
        $msg = $raw | ConvertFrom-Json
        $px = [int]$bounds.X + [Math]::Max(0, [Math]::Min($bounds.Width - 1, [int]$msg.x))
        $py = [int]$bounds.Y + [Math]::Max(0, [Math]::Min($bounds.Height - 1, [int]$msg.y))
        $kind = 0
        if ($msg.kind -eq 'down') { $kind = 1; $script:held = $true }
        elseif ($msg.kind -eq 'up') { $kind = 2; $script:held = $false }
        [AdbMon]::Pointer($px, $py, $kind, $virtual.Left, $virtual.Top, $virtual.Width, $virtual.Height) | Out-Null
        $res.StatusCode = 204
      } else {
        $res.StatusCode = 404
      }
    } catch {
      $res.StatusCode = 500
    } finally {
      $res.OutputStream.Close()
    }
  }
} finally {
  $listener.Stop()
}
