"""60 benchmark prompts for image generation bake-off (GenEval style).
Each prompt has checkable attributes verified by Gemma 4 vision judge.
Categories:
- single object (10)
- two objects (10)
- counting 2-4 (10)
- colours (10)
- position / spatial (10)
- user styles (10)
"""

PROMPTS = [
    # =========================================================================
    # 1. SINGLE OBJECT (10)
    # =========================================================================
    {
        "id": "single_01",
        "category": "single_object",
        "prompt": "a red bicycle parked on a sidewalk",
        "checks": [("Is there a bicycle in the image?", "yes"), ("Is the bicycle red?", "yes")]
    },
    {
        "id": "single_02",
        "category": "single_object",
        "prompt": "a wooden acoustic guitar resting against a wall",
        "checks": [("Is there an acoustic guitar in the image?", "yes")]
    },
    {
        "id": "single_03",
        "category": "single_object",
        "prompt": "a ceramic white coffee mug on a wooden desk",
        "checks": [("Is there a coffee mug or cup in the image?", "yes"), ("Is the mug white?", "yes")]
    },
    {
        "id": "single_04",
        "category": "single_object",
        "prompt": "a slice of chocolate cake on a dessert plate",
        "checks": [("Is there a slice of cake in the image?", "yes")]
    },
    {
        "id": "single_05",
        "category": "single_object",
        "prompt": "a vintage analog alarm clock showing 7 o'clock",
        "checks": [("Is there an alarm clock in the image?", "yes")]
    },
    {
        "id": "single_06",
        "category": "single_object",
        "prompt": "a green backpack on a hiking trail",
        "checks": [("Is there a backpack in the image?", "yes"), ("Is the backpack green?", "yes")]
    },
    {
        "id": "single_07",
        "category": "single_object",
        "prompt": "a pair of blue running shoes on a wooden floor",
        "checks": [("Are there shoes or sneakers in the image?", "yes"), ("Are the shoes blue?", "yes")]
    },
    {
        "id": "single_08",
        "category": "single_object",
        "prompt": "a glass vase with fresh water",
        "checks": [("Is there a glass vase in the image?", "yes")]
    },
    {
        "id": "single_09",
        "category": "single_object",
        "prompt": "a ripe yellow pineapple on a marble kitchen counter",
        "checks": [("Is there a pineapple in the image?", "yes")]
    },
    {
        "id": "single_10",
        "category": "single_object",
        "prompt": "a black leather armchair in a living room",
        "checks": [("Is there an armchair or chair in the image?", "yes")]
    },

    # =========================================================================
    # 2. TWO OBJECTS (10)
    # =========================================================================
    {
        "id": "two_01",
        "category": "two_objects",
        "prompt": "a cat and a dog sitting peacefully side by side",
        "checks": [("Is there a cat in the image?", "yes"), ("Is there a dog in the image?", "yes")]
    },
    {
        "id": "two_02",
        "category": "two_objects",
        "prompt": "an apple and a banana on a wooden chopping board",
        "checks": [("Is there an apple in the image?", "yes"), ("Is there a banana in the image?", "yes")]
    },
    {
        "id": "two_03",
        "category": "two_objects",
        "prompt": "a laptop computer and a smartphone on a clean desk",
        "checks": [("Is there a laptop in the image?", "yes"), ("Is there a phone in the image?", "yes")]
    },
    {
        "id": "two_04",
        "category": "two_objects",
        "prompt": "a cup of tea and a book with glasses on top",
        "checks": [("Is there a cup or mug in the image?", "yes"), ("Is there a book in the image?", "yes")]
    },
    {
        "id": "two_05",
        "category": "two_objects",
        "prompt": "a basketball and a tennis racket on a sports court",
        "checks": [("Is there a basketball in the image?", "yes"), ("Is there a racket in the image?", "yes")]
    },
    {
        "id": "two_06",
        "category": "two_objects",
        "prompt": "a pair of sunglasses next to a straw hat",
        "checks": [("Are there sunglasses in the image?", "yes"), ("Is there a hat in the image?", "yes")]
    },
    {
        "id": "two_07",
        "category": "two_objects",
        "prompt": "a bottle of wine and a wine glass on a dinner table",
        "checks": [("Is there a bottle in the image?", "yes"), ("Is there a glass in the image?", "yes")]
    },
    {
        "id": "two_08",
        "category": "two_objects",
        "prompt": "a teddy bear and a toy wooden train on a rug",
        "checks": [("Is there a teddy bear in the image?", "yes"), ("Is there a toy train in the image?", "yes")]
    },
    {
        "id": "two_09",
        "category": "two_objects",
        "prompt": "a chef knife and a red bell pepper on a cutting board",
        "checks": [("Is there a knife in the image?", "yes"), ("Is there a bell pepper in the image?", "yes")]
    },
    {
        "id": "two_10",
        "category": "two_objects",
        "prompt": "a potted succulent plant next to a burning candle",
        "checks": [("Is there a plant or succulent in the image?", "yes"), ("Is there a candle in the image?", "yes")]
    },

    # =========================================================================
    # 3. COUNTING 2-4 (10)
    # =========================================================================
    {
        "id": "count_01",
        "category": "counting",
        "prompt": "exactly two red apples sitting on a white plate",
        "checks": [("Are there red apples in the image?", "yes"), ("Are there approximately two apples?", "yes")]
    },
    {
        "id": "count_02",
        "category": "counting",
        "prompt": "three yellow rubber ducks floating in a bathtub",
        "checks": [("Are there rubber ducks in the image?", "yes"), ("Are there ducks visible?", "yes")]
    },
    {
        "id": "count_03",
        "category": "counting",
        "prompt": "four colorful balloons tied to a park bench",
        "checks": [("Are there balloons in the image?", "yes")]
    },
    {
        "id": "count_04",
        "category": "counting",
        "prompt": "two wooden chairs around a small round table",
        "checks": [("Are there chairs in the image?", "yes")]
    },
    {
        "id": "count_05",
        "category": "counting",
        "prompt": "three glass bottles lined up in a row",
        "checks": [("Are there bottles in the image?", "yes")]
    },
    {
        "id": "count_06",
        "category": "counting",
        "prompt": "two golden retriever puppies playing together",
        "checks": [("Are there dogs or puppies in the image?", "yes")]
    },
    {
        "id": "count_07",
        "category": "counting",
        "prompt": "four chocolate cupcakes on a dessert stand",
        "checks": [("Are there cupcakes in the image?", "yes")]
    },
    {
        "id": "count_08",
        "category": "counting",
        "prompt": "three red roses in a transparent glass vase",
        "checks": [("Are there roses in the image?", "yes")]
    },
    {
        "id": "count_09",
        "category": "counting",
        "prompt": "two ceramic mugs side by side",
        "checks": [("Are there mugs in the image?", "yes")]
    },
    {
        "id": "count_010",
        "category": "counting",
        "prompt": "three bright yellow lemons on a clean counter",
        "checks": [("Are there lemons in the image?", "yes")]
    },

    # =========================================================================
    # 4. COLOURS (10)
    # =========================================================================
    {
        "id": "color_01",
        "category": "colors",
        "prompt": "a bright yellow sports car driving on an asphalt road",
        "checks": [("Is there a sports car in the image?", "yes"), ("Is the car yellow?", "yes")]
    },
    {
        "id": "color_02",
        "category": "colors",
        "prompt": "a purple umbrella opened in the rain",
        "checks": [("Is there an umbrella in the image?", "yes"), ("Is the umbrella purple or violet?", "yes")]
    },
    {
        "id": "color_03",
        "category": "colors",
        "prompt": "an orange cat sleeping peacefully on a blue sofa",
        "checks": [("Is there a cat in the image?", "yes"), ("Is the sofa or background blue?", "yes")]
    },
    {
        "id": "color_04",
        "category": "colors",
        "prompt": "a green apple next to a red strawberry",
        "checks": [("Is there a green apple in the image?", "yes"), ("Is there a red strawberry in the image?", "yes")]
    },
    {
        "id": "color_05",
        "category": "colors",
        "prompt": "a pink flamingo standing in shallow blue water",
        "checks": [("Is there a flamingo in the image?", "yes"), ("Is the flamingo pink?", "yes")]
    },
    {
        "id": "color_06",
        "category": "colors",
        "prompt": "a dark blue velvet blazer on a wooden mannequin",
        "checks": [("Is there a blazer or jacket in the image?", "yes"), ("Is it blue?", "yes")]
    },
    {
        "id": "color_07",
        "category": "colors",
        "prompt": "a golden yellow sunflower under a clear bright blue sky",
        "checks": [("Is there a sunflower in the image?", "yes"), ("Is the sky blue?", "yes")]
    },
    {
        "id": "color_08",
        "category": "colors",
        "prompt": "a red sports helmet with white racing stripes",
        "checks": [("Is there a helmet in the image?", "yes"), ("Is the helmet red?", "yes")]
    },
    {
        "id": "color_09",
        "category": "colors",
        "prompt": "a turquoise teal suitcase standing upright on airport floor",
        "checks": [("Is there a suitcase or luggage in the image?", "yes"), ("Is it teal or turquoise or blue?", "yes")]
    },
    {
        "id": "color_10",
        "category": "colors",
        "prompt": "a vibrant green frog sitting on a dark green lily pad",
        "checks": [("Is there a frog in the image?", "yes"), ("Is the frog green?", "yes")]
    },

    # =========================================================================
    # 5. SPATIAL / POSITION (10)
    # =========================================================================
    {
        "id": "spatial_01",
        "category": "spatial",
        "prompt": "a cat sitting on top of a wooden table",
        "checks": [("Is there a cat on top of a table?", "yes")]
    },
    {
        "id": "spatial_02",
        "category": "spatial",
        "prompt": "a dog sleeping under a wooden dining chair",
        "checks": [("Is there a dog near or under a chair?", "yes")]
    },
    {
        "id": "spatial_03",
        "category": "spatial",
        "prompt": "an airplane flying above white fluffy clouds in the sky",
        "checks": [("Is there an airplane in the sky?", "yes"), ("Are there clouds below or around it?", "yes")]
    },
    {
        "id": "spatial_04",
        "category": "spatial",
        "prompt": "a red cup on the left and a blue cup on the right",
        "checks": [("Are there two cups in the image?", "yes")]
    },
    {
        "id": "spatial_05",
        "category": "spatial",
        "prompt": "a pair of glasses placed on top of an open hardcover book",
        "checks": [("Are glasses resting on a book?", "yes")]
    },
    {
        "id": "spatial_06",
        "category": "spatial",
        "prompt": "a laptop on a wooden desk with a lamp standing to the right",
        "checks": [("Is there a laptop on a desk?", "yes"), ("Is there a lamp?", "yes")]
    },
    {
        "id": "spatial_07",
        "category": "spatial",
        "prompt": "a bird perched on a wooden fence post",
        "checks": [("Is a bird perched on a fence or branch?", "yes")]
    },
    {
        "id": "spatial_08",
        "category": "spatial",
        "prompt": "a small boat floating on the water in front of mountains",
        "checks": [("Is there a boat on the water?", "yes"), ("Are there mountains in the background?", "yes")]
    },
    {
        "id": "spatial_09",
        "category": "spatial",
        "prompt": "a wall clock hanging directly above a doorway",
        "checks": [("Is there a clock on a wall?", "yes")]
    },
    {
        "id": "spatial_10",
        "category": "spatial",
        "prompt": "shoes placed neatly in front of an entrance door",
        "checks": [("Are there shoes in the image?", "yes")]
    },

    # =========================================================================
    # 6. USER STYLES (10)
    # =========================================================================
    {
        "id": "style_01",
        "category": "styles",
        "prompt": "a cat astronaut floating in outer space, watercolor painting style",
        "checks": [("Is there a cat or astronaut in the image?", "yes")]
    },
    {
        "id": "style_02",
        "category": "styles",
        "prompt": "a brave samurai warrior standing on a mountain peak, anime manga art style",
        "checks": [("Is there a warrior or anime character?", "yes")]
    },
    {
        "id": "style_03",
        "category": "styles",
        "prompt": "a medieval stone castle, 16-bit retro pixel art style",
        "checks": [("Is there a castle in the image?", "yes")]
    },
    {
        "id": "style_04",
        "category": "styles",
        "prompt": "a steaming cup of espresso, professional food photography, shallow depth of field",
        "checks": [("Is there coffee or espresso in the image?", "yes")]
    },
    {
        "id": "style_05",
        "category": "styles",
        "prompt": "a minimalist geometric logo of a howling wolf, vector graphics style",
        "checks": [("Is there a wolf or animal logo?", "yes")]
    },
    {
        "id": "style_06",
        "category": "styles",
        "prompt": "Diwali festival celebration with glowing clay oil lamps diyas and marigold flowers",
        "checks": [("Are there lamps or diyas in the image?", "yes"), ("Is there a warm festive glow?", "yes")]
    },
    {
        "id": "style_07",
        "category": "styles",
        "prompt": "Holi festival of colors with two laughing friends throwing vibrant powders in the air",
        "checks": [("Are there colorful powders in the image?", "yes")]
    },
    {
        "id": "style_08",
        "category": "styles",
        "prompt": "a vintage 1970s Polaroid instant photo of a beach picnic with friends",
        "checks": [("Is there a beach or picnic scene in the image?", "yes")]
    },
    {
        "id": "style_09",
        "category": "styles",
        "prompt": "detailed charcoal pencil sketch of an elderly man with kind eyes and wrinkles",
        "checks": [("Is there a portrait or face in the image?", "yes")]
    },
    {
        "id": "style_10",
        "category": "styles",
        "prompt": "cyberpunk city street at night in heavy neon rain, octane render 3D art style",
        "checks": [("Is there a city street with lights or neon in the image?", "yes")]
    },
]
