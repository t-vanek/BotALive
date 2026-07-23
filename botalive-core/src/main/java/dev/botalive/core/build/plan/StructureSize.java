package dev.botalive.core.build.plan;

/**
 * Rozměry stavby: šířka (X), hloubka (Z) a výška zdí. Čistý datový typ sdílený
 * napříč staviteli i persistencí – „jak velká stavba je", odděleně od toho, jaká
 * je (geometrie {@link Blueprint}) a z čeho (materiály {@link Palette}).
 *
 * @param width      šířka půdorysu v ose X
 * @param depth      hloubka půdorysu v ose Z (u čtvercových staveb = {@code width})
 * @param wallHeight výška zdí
 */
public record StructureSize(int width, int depth, int wallHeight) {

    /** @return větší z půdorysných rozměrů (pro rezervaci parcel a odsazení). */
    public int footprintSpan() {
        return Math.max(width, depth);
    }

    /**
     * Je tento rozměr aspoň v jedné ose větší než {@code other}? Slouží
     * monotónnímu růstu (roste se, jen když je cíl větší než postavené).
     *
     * @param other porovnávaný (typicky postavený) rozměr
     * @return {@code true} když je tento širší, hlubší nebo vyšší
     */
    public boolean exceeds(StructureSize other) {
        return width > other.width || depth > other.depth || wallHeight > other.wallHeight;
    }
}
