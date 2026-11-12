using System.Globalization;
using System.Text;
using Microsoft.AspNetCore.WebUtilities;
using OnFinApp.Api.Common;

namespace OnFinApp.Api.Instruments;

/// Position in a search result: the rank and ticker of the last item already returned.
/// Clients see it as an opaque base64url string.
public readonly record struct InstrumentCursor(int Rank, string Symbol)
{
    /// Before the first item.
    public static readonly InstrumentCursor Start = new(-1, "");

    public string Encode() => WebEncoders.Base64UrlEncode(Encoding.UTF8.GetBytes($"{Rank}:{Symbol}"));

    /// Throws <see cref="InvalidCursorException"/> for anything that <see cref="Encode"/> did not produce.
    public static InstrumentCursor Decode(string cursor)
    {
        try
        {
            var raw = Encoding.UTF8.GetString(WebEncoders.Base64UrlDecode(cursor));
            var colon = raw.IndexOf(':');
            if (colon > 0
                && int.TryParse(raw.AsSpan(0, colon), NumberStyles.None, CultureInfo.InvariantCulture, out var rank)
                && rank is >= 0 and <= 2
                && colon < raw.Length - 1)
            {
                return new InstrumentCursor(rank, raw[(colon + 1)..]);
            }
        }
        catch (FormatException)
        {
            // not base64url: falls through to the error below
        }
        throw new InvalidCursorException();
    }
}
