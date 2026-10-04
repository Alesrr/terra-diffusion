package com.github.xandergos.terraindiffusionmc.explorer;

import java.util.HashMap;
import java.util.Map;

final class BiomePalette {
    record Entry(short id, String name, int grass, int display) {}

    private static final Entry UNKNOWN = new Entry((short) -1, "Unknown", 0x808080, 0xFF00FF);

    private static final Map<Short, Entry> ENTRIES = new HashMap<>();

    private static void add(Entry... entries) {
        for (Entry e : entries) ENTRIES.put(e.id(), e);
    }

    private static Entry entry(int id, String name, int grass, int display) {
        return new Entry((short) id, name, grass, display);
    }

    static {
        add(
            entry(1, "Plains", 0x91BD59, 0x91BD59),
            entry(3, "Snowy Plains", 0x80B497, 0xF1F5F8),
            entry(5, "Desert", 0xBFB755, 0xE3D49A),
            entry(6, "Swamp", 0x6A7039, 0x6A7039),
            entry(7, "River", 0x8EB971, 0x4C8FD6),
            entry(8, "Forest", 0x79C05A, 0x79C05A),
            entry(11, "Frozen River", 0x80B497, 0x9DB7D6),
            entry(15, "Taiga", 0x86B783, 0x86B783),
            entry(16, "Snowy Taiga", 0x80B497, 0xC9DCD6),
            entry(17, "Savanna", 0xBFB755, 0xBFB755),
            entry(19, "Windswept Hills", 0x8AB689, 0x8AB689),
            entry(23, "Jungle", 0x59C93C, 0x59C93C),
            entry(26, "Badlands", 0x90814D, 0xB8683E),
            entry(29, "Meadow", 0x83BB6D, 0x83BB6D),
            entry(31, "Grove", 0x80B497, 0xC9DCD6),
            entry(32, "Snowy Slopes", 0x80B497, 0xF1F5F8),
            entry(33, "Frozen Peaks", 0x80B497, 0xF1F5F8),
            entry(35, "Stony Peaks", 0x9ABE4B, 0x9ABE4B),
            entry(41, "Warm Ocean", 0x8EB971, 0x2F8FC0),
            entry(44, "Ocean", 0x8EB971, 0x3A76B8),
            entry(46, "Cold Ocean", 0x8EB971, 0x3B6A9E),
            entry(48, "Frozen Ocean", 0x80B497, 0x9DB7D6),
            entry(108, "Sparse Forest", 0x79C05A, 0x79C05A),
            entry(115, "Sparse Taiga", 0x86B783, 0x86B783),
            entry(116, "Sparse Snowy Taiga", 0x80B497, 0xC9DCD6),
            entry(200, "Deep Warm Ocean", 0x8EB971, 0x1E6FA6),
            entry(201, "Gravel Beach", 0x91BD59, 0x91BD59),
            entry(202, "Warm River", 0x8EB971, 0x4C8FD6),
            entry(210, "Frozen Cliffs", 0x80B497, 0xA9C8F0),
            entry(211, "Granite Cliffs", 0x92B77A, 0x92B77A),
            entry(212, "White Cliffs", 0x92B77A, 0x92B77A),
            entry(213, "Yosemite Cliffs", 0x85B979, 0x85B979),
            entry(214, "Basalt Cliffs", 0x95B872, 0x5B5B60),
            entry(215, "Stony Spires", 0x98BB63, 0x98BB63),
            entry(216, "Windswept Spires", 0x8AB689, 0x8AB689),
            entry(217, "Desert Spires", 0x77B338, 0xE3D49A),
            entry(218, "Desert Canyon", 0xBFB755, 0xE3D49A),
            entry(219, "Bryce Canyon", 0x90814D, 0xB8683E),
            entry(220, "Amethyst Canyon", 0x70D484, 0x70D484),
            entry(221, "Glacial Chasm", 0x80B497, 0xA9C8F0),
            entry(230, "Volcanic Peaks", 0xC0BBAC, 0x3D3A3E),
            entry(231, "Volcanic Crater", 0xC0BBAC, 0x3D3A3E),
            entry(232, "Caldera", 0xC6D097, 0xC6D097),
            entry(233, "Emerald Peaks", 0x6783AF, 0xF1F5F8),
            entry(234, "Scarlet Mountains", 0x901222, 0x901222),
            entry(235, "Rocky Mountains", 0x8EB681, 0x8EB681),
            entry(236, "Painted Mountains", 0x9ABE4B, 0xC77A55),
            entry(237, "Haze Mountain", 0x9FA885, 0x9FA885),
            entry(238, "Jungle Mountains", 0x72AA06, 0x72AA06),
            entry(239, "Alpine Grove", 0x80B497, 0xC9DCD6),
            entry(240, "Cloud Forest", 0x5C9A70, 0x5C9A70),
            entry(241, "Yellowstone", 0xC6D097, 0xC6D097),
            entry(250, "Alpine Highlands", 0x99B678, 0x99B678),
            entry(251, "Temperate Highlands", 0xA4BB58, 0xA4BB58),
            entry(252, "Forested Highlands", 0x93B67E, 0x93B67E),
            entry(253, "Arid Highlands", 0xB2B952, 0xB2B952),
            entry(254, "Highlands", 0x8FB87A, 0x8FB87A),
            entry(255, "Blooming Plateau", 0x83BB6D, 0x83BB6D),
            entry(256, "Shield", 0x70A57B, 0x70A57B),
            entry(257, "Shield Clearing", 0x99B67C, 0x99B67C),
            entry(258, "Snowy Shield", 0x70A57B, 0xC9DCD6),
            entry(259, "Siberian Grove", 0x4D836D, 0xC9DCD6),
            entry(260, "Rocky Shrubland", 0x937554, 0xDCD6CC),
            entry(270, "Savanna Slopes", 0xBFB755, 0xC9B26A),
            entry(271, "Savanna Badlands", 0xBFB755, 0xC9B26A),
            entry(272, "Ashen Savanna", 0xC0BBAC, 0xB5B0A4),
            entry(273, "Fractured Savanna", 0xBFB755, 0xBFB755),
            entry(274, "Brushland", 0xA7BB4E, 0xA7BB4E),
            entry(275, "Shrubland", 0xAE9967, 0xAE9967),
            entry(276, "Hot Shrubland", 0xC3AF67, 0xC3AF67),
            entry(277, "Cold Shrubland", 0x937554, 0xDCD6CC),
            entry(278, "Steppe", 0x99B67C, 0x99B67C),
            entry(290, "Ancient Sands", 0xBFB755, 0xD8B77A),
            entry(291, "Lush Desert", 0xDCAC56, 0xD9B071),
            entry(292, "Desert Oasis", 0x77B338, 0x9CC25A),
            entry(293, "Red Oasis", 0x77B338, 0x9CC25A),
            entry(294, "Sandstone Valley", 0x77B338, 0xE3D49A),
            entry(295, "White Mesa", 0x90814D, 0xD8C8B8),
            entry(296, "Warped Mesa", 0xBEAFDD, 0xA78BD0),
            entry(297, "Gravel Desert", 0x86B68C, 0xF1F5F8),
            entry(298, "Snowy Badlands", 0x80B497, 0xE6D6CF),
            entry(310, "Tropical Jungle", 0x89DD4E, 0x89DD4E),
            entry(311, "Rocky Jungle", 0x72AA06, 0x72AA06),
            entry(312, "Amethyst Rainforest", 0x70D484, 0x70D484),
            entry(320, "Yosemite Lowlands", 0x598347, 0x598347),
            entry(321, "Lush Valley", 0x70A57B, 0x70A57B),
            entry(322, "Valley Clearing", 0x99B67C, 0x99B67C),
            entry(323, "Birch Taiga", 0x8DC876, 0x8DC876),
            entry(324, "Lavender Forest", 0x9BE0BC, 0x9BE0BC),
            entry(325, "Lavender Valley", 0x9BE0BC, 0x9BE0BC),
            entry(326, "Sakura Grove", 0x79CF85, 0x79CF85),
            entry(327, "Sakura Valley", 0x79CF85, 0x79CF85),
            entry(328, "Moonlight Grove", 0x9EB2E1, 0x9EB2E1),
            entry(329, "Moonlight Valley", 0x9EB2E1, 0x9EB2E1),
            entry(330, "Blooming Valley", 0x79C05A, 0x79C05A),
            entry(331, "Orchid Swamp", 0x5F9B76, 0x5F9B76),
            entry(340, "Siberian Taiga", 0x629E77, 0xC9DCD6),
            entry(341, "Wintry Forest", 0x80B497, 0xC9DCD6),
            entry(342, "Wintry Lowlands", 0x80B497, 0xF1F5F8),
            entry(343, "Snowy Maple Forest", 0x85B590, 0xC9DCD6),
            entry(344, "Snowy Cherry Grove", 0xB6DB61, 0xF1F5F8),
            entry(345, "Ice Marsh", 0x6A7039, 0xF1F5F8)
        );
    }

    private BiomePalette() {
    }

    static Entry of(short id) {
        return ENTRIES.getOrDefault(id, UNKNOWN);
    }

    static boolean isWater(short id) {
        return id == 7 || id == 11 || id == 41 || id == 44 || id == 46 || id == 48 || id == 200 || id == 202;
    }
}
