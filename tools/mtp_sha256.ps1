# SHA-256 of a file ON the phone, read directly over MTP (USB) as a stream.
# Nothing is copied to disk: bytes go phone -> memory -> SHA-256.
# Walks This PC > <Device> > Internal storage > Download > RecVault.apk by display name.
param(
  [string]$Device = "S24",
  [string[]]$Path = @("Internal storage", "Download", "RecVault.apk")
)
$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using System.Security.Cryptography;

public static class MtpHash {
    [ComImport, Guid("43826d1e-e718-42ee-bc55-a1e261c37bfe"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IShellItem {
        void BindToHandler(IBindCtx pbc, [MarshalAs(UnmanagedType.LPStruct)] Guid bhid,
                           [MarshalAs(UnmanagedType.LPStruct)] Guid riid, [MarshalAs(UnmanagedType.Interface)] out object ppv);
        void GetParent(out IShellItem ppsi);
        void GetDisplayName(uint sigdnName, [MarshalAs(UnmanagedType.LPWStr)] out string ppszName);
        void GetAttributes(uint sfgaoMask, out uint psfgaoAttribs);
        void Compare(IShellItem psi, uint hint, out int piOrder);
    }

    [ComImport, Guid("70629033-e363-4a28-a567-0db78006e6d7"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IEnumShellItems {
        [PreserveSig] int Next(uint celt, [MarshalAs(UnmanagedType.Interface)] out IShellItem rgelt, out uint pceltFetched);
        void Skip(uint celt);
        void Reset();
        void Clone(out IEnumShellItems ppenum);
    }

    [DllImport("shell32.dll", PreserveSig = false)]
    static extern void SHGetKnownFolderItem([MarshalAs(UnmanagedType.LPStruct)] Guid rfid, uint flags, IntPtr hToken,
        [MarshalAs(UnmanagedType.LPStruct)] Guid riid, [MarshalAs(UnmanagedType.Interface)] out IShellItem ppv);

    [DllImport("ole32.dll", PreserveSig = false)]
    static extern void CreateBindCtx(uint reserved, out IBindCtx ppbc);

    static readonly Guid FOLDERID_ComputerFolder = new Guid("0AC0837C-BBF8-452A-850D-79D08E667CA7");
    static readonly Guid BHID_EnumItems = new Guid("94f60519-2850-4924-aa5a-d15e84868039");
    static readonly Guid BHID_Stream = new Guid("1CEBB3AB-7C10-499a-A417-92CA16C4CB83");
    static readonly Guid IID_IStream = new Guid("0000000c-0000-0000-C000-000000000046");
    static readonly Guid IID_IShellItem = new Guid("43826d1e-e718-42ee-bc55-a1e261c37bfe");
    static readonly Guid IID_IEnumShellItems = new Guid("70629033-e363-4a28-a567-0db78006e6d7");
    const uint SIGDN_NORMALDISPLAY = 0;

    static IShellItem Child(IShellItem parent, string name, bool contains) {
        object o;
        parent.BindToHandler(null, BHID_EnumItems, IID_IEnumShellItems, out o);
        var en = (IEnumShellItems)o;
        IShellItem it; uint got;
        while (en.Next(1, out it, out got) == 0 && got == 1) {
            string dn; it.GetDisplayName(SIGDN_NORMALDISPLAY, out dn);
            if (contains ? dn.Contains(name) : dn == name) return it;
        }
        throw new Exception("not found on phone: " + name);
    }

    public static string Hash(string device, string[] path, out long total) {
        IShellItem cur;
        SHGetKnownFolderItem(FOLDERID_ComputerFolder, 0, IntPtr.Zero, IID_IShellItem, out cur);
        cur = Child(cur, device, true);
        foreach (var p in path) cur = Child(cur, p, false);

        IBindCtx ctx; CreateBindCtx(0, out ctx);
        var bo = new System.Runtime.InteropServices.ComTypes.BIND_OPTS();
        bo.cbStruct = Marshal.SizeOf(typeof(System.Runtime.InteropServices.ComTypes.BIND_OPTS));
        bo.grfMode = 0; // STGM_READ
        ctx.SetBindOptions(ref bo);
        object so;
        cur.BindToHandler(ctx, BHID_Stream, IID_IStream, out so);
        var s = (IStream)so;

        byte[] buf = new byte[1 << 20];
        IntPtr pRead = Marshal.AllocHGlobal(4);
        total = 0;
        try {
            using (var sha = SHA256.Create()) {
                while (true) {
                    s.Read(buf, buf.Length, pRead);
                    int n = Marshal.ReadInt32(pRead);
                    if (n <= 0) break;
                    sha.TransformBlock(buf, 0, n, null, 0);
                    total += n;
                }
                sha.TransformFinalBlock(new byte[0], 0, 0);
                return BitConverter.ToString(sha.Hash).Replace("-", "");
            }
        } finally { Marshal.FreeHGlobal(pRead); }
    }
}
"@

$len = 0L
$h = [MtpHash]::Hash($Device, $Path, [ref]$len)
"PHONE (read in place over USB): $h  $len bytes"
