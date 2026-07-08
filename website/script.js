// Initialize GSAP, ScrollTrigger and MotionPath
gsap.registerPlugin(ScrollTrigger, MotionPathPlugin);

// Slideshow state variables
let currentSlide = 1;
const totalSlides = 4;
let slideInterval;

// 1. Navbar and Hero Entrance Animation
window.addEventListener('DOMContentLoaded', () => {
    const tl = gsap.timeline({ defaults: { ease: 'power4.out', duration: 1.2 } });
    
    tl.from('nav', { y: -100, opacity: 0 })
      .from('.hero h1', { y: 60, opacity: 0, stagger: 0.15 }, '-=0.8')
      .from('.hero p', { y: 30, opacity: 0 }, '-=0.9')
      .from('.cta-button', { scale: 0.85, opacity: 0 }, '-=1')
      .from('.pipeline-svg-container', { y: 40, opacity: 0, duration: 1.5 }, '-=1.2');

    // Init all animations
    initFloatingLanguages();
    initScrollAnimations();
    initInteractivePipeline();
    initRotatingText();
    
    // Start automatic slideshow
    resetInterval();
});

// 2. Dynamic GSAP Floating Languages (Parallax + Hover effect)
function initFloatingLanguages() {
    const floatWords = document.querySelectorAll('.float-word');
    
    floatWords.forEach((word) => {
        // Random placement within quadrants
        const xStart = Math.random() * window.innerWidth;
        const yStart = Math.random() * (window.innerHeight * 0.7) + 80;
        
        gsap.set(word, { x: xStart, y: yStart, opacity: 0, scale: gsap.utils.random(0.7, 1.2) });
        
        // Random continuous floating path
        const duration = gsap.utils.random(15, 30);
        const floatTimeline = gsap.timeline({ repeat: -1 });

        floatTimeline.to(word, {
            opacity: gsap.utils.random(0.08, 0.25),
            duration: duration * 0.1,
            ease: 'power1.inOut'
        }).to(word, {
            x: `+=${gsap.utils.random(-150, 150)}`,
            y: `+=${gsap.utils.random(-150, 150)}`,
            rotation: gsap.utils.random(-25, 25),
            duration: duration * 0.8,
            ease: 'sine.inOut'
        }).to(word, {
            opacity: 0,
            duration: duration * 0.1,
            ease: 'power1.inOut'
        });

        // Interactive mouse hover
        word.style.pointerEvents = 'auto';
        word.addEventListener('mouseenter', () => {
            gsap.to(word, {
                color: '#E8593C', // Accent Coral
                opacity: 0.7,
                scale: '+=0.2',
                duration: 0.4,
                overwrite: 'auto'
            });
        });

        word.addEventListener('mouseleave', () => {
            gsap.to(word, {
                color: 'rgba(255, 255, 255, 0.03)',
                opacity: gsap.utils.random(0.08, 0.25),
                scale: '-=0.2',
                duration: 0.8,
                overwrite: 'auto'
            });
        });
    });
}

// 3. ScrollTrigger Animations for Tech Stack and Bento Header
function initScrollAnimations() {
    // Staggered fade-up for tech stack items
    gsap.from('.tech-item', {
        scrollTrigger: {
            trigger: '.tech-stack',
            start: 'top 85%',
            toggleActions: 'play none none none'
        },
        y: 50,
        opacity: 0,
        stagger: 0.2,
        duration: 1,
        ease: 'power3.out'
    });

    // Sections fade-in
    gsap.from('.section-header h2, .section-header p', {
        scrollTrigger: {
            trigger: '.section-header',
            start: 'top 90%',
            toggleActions: 'play none none none'
        },
        y: 30,
        opacity: 0,
        stagger: 0.1,
        duration: 0.8
    });
}

// 4. Custom GSAP Interactive Slideshow
function animateSlideSwitch(slideIndex) {
    const currentImg = document.querySelector('.slide-image.active');
    const targetImg = document.querySelector(`#slide-img-${slideIndex}`);
    const featureBlocks = document.querySelectorAll('.feature-block');

    if (currentImg === targetImg) return;

    // Slide transition for images (zoom-out/fade-out & zoom-in/fade-in)
    if (currentImg) {
        gsap.to(currentImg, {
            opacity: 0,
            scale: 1.05,
            duration: 0.6,
            ease: 'power2.inOut',
            onComplete: () => currentImg.classList.remove('active')
        });
    }

    if (targetImg) {
        targetImg.classList.add('active');
        gsap.fromTo(targetImg, 
            { opacity: 0, scale: 0.95 },
            { opacity: 1, scale: 1, duration: 0.8, ease: 'power3.out' }
        );
    }

    // Active block class updates
    featureBlocks.forEach((block, index) => {
        if (index + 1 === slideIndex) {
            block.classList.add('active');
            gsap.to(block, {
                borderColor: 'rgba(255, 255, 255, 0.12)',
                x: 0,
                opacity: 1,
                duration: 0.4
            });
        } else {
            block.classList.remove('active');
            gsap.to(block, {
                borderColor: 'transparent',
                x: -15,
                opacity: 0.4,
                duration: 0.4
            });
        }
    });
}

function nextSlide() {
    currentSlide = currentSlide >= totalSlides ? 1 : currentSlide + 1;
    animateSlideSwitch(currentSlide);
}

function resetInterval() {
    clearInterval(slideInterval);
    slideInterval = setInterval(nextSlide, 5000);
}

// Override global switchSlide function so it interfaces with our GSAP implementation
window.switchSlide = function(slideIndex) {
    animateSlideSwitch(slideIndex);
    currentSlide = slideIndex;
    resetInterval();
};

// 5. Interactive SVG pipeline animation
function initInteractivePipeline() {
    // Pulse animation for processor node (Gemma Chip)
    gsap.to('#gemma-node', {
        scale: 1.05,
        transformOrigin: '50% 50%',
        duration: 1.5,
        repeat: -1,
        yoyo: true,
        ease: 'sine.inOut'
    });

    // Wave animations (Mic Waves / Sound Output Waves)
    gsap.to('.mic-wave', {
        opacity: 0.3,
        scale: 1.1,
        transformOrigin: '50% 50%',
        stagger: 0.3,
        duration: 1,
        repeat: -1,
        yoyo: true,
        ease: 'power1.inOut'
    });

    // Dynamic wave paths for speaker audio waves
    gsap.to('.speaker-wave', {
        opacity: 0.2,
        x: 4,
        stagger: 0.25,
        duration: 0.8,
        repeat: -1,
        yoyo: true,
        ease: 'sine.inOut'
    });

    // Particle flow animation through pipeline path
    gsap.to('#data-particle', {
        motionPath: {
            path: '#pipeline-path',
            align: '#pipeline-path',
            alignOrigin: [0.5, 0.5]
        },
        duration: 4,
        ease: 'power1.inOut',
        repeat: -1
    });
}

// 6. GSAP Rotating Words Animation (Text flip effect)
function initRotatingText() {
    const words = ["translation.", "traducción.", "traduction.", "übersetzung.", "ترجمة.", "翻译."];
    let wordIndex = 0;
    const rotatingText = document.querySelector('.rotating-text');
    
    if (rotatingText) {
        setInterval(() => {
            const tl = gsap.timeline();
            tl.to(rotatingText, {
                y: -15,
                opacity: 0,
                duration: 0.35,
                ease: 'power2.in',
                onComplete: () => {
                    wordIndex = (wordIndex + 1) % words.length;
                    rotatingText.textContent = words[wordIndex];
                }
            }).fromTo(rotatingText,
                { y: 15, opacity: 0 },
                { y: 0, opacity: 1, duration: 0.5, ease: 'back.out(1.5)' }
            );
        }, 3000);
    }
}
