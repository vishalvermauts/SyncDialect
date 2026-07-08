// Register GSAP plugins
gsap.registerPlugin(MotionPathPlugin);

window.addEventListener('DOMContentLoaded', () => {
    // 1. Motion Path Animation
    const particle = document.getElementById('motion-particle');
    const path = document.getElementById('motion-path');
    
    if (particle && path) {
        gsap.to(particle, {
            motionPath: {
                path: path,
                align: path,
                alignOrigin: [0.5, 0.5]
            },
            duration: 15,
            ease: "none",
            repeat: -1
        });
    }

    // 2. Broken Text Hover Effect
    const brokenElements = document.querySelectorAll('.broken-text');

    brokenElements.forEach(element => {
        const text = element.textContent.trim();
        element.innerHTML = ''; // clear original

        // Split text into characters
        [...text].forEach(char => {
            const span = document.createElement('span');
            if (char === ' ') {
                span.innerHTML = '&nbsp;';
            } else {
                span.textContent = char;
            }
            span.className = 'broken-char';
            span.style.display = 'inline-block';
            span.style.transformOrigin = 'center';
            span.style.transition = 'color 0.2s ease';
            element.appendChild(span);
        });

        // Hover animations on the container
        const chars = element.querySelectorAll('.broken-char');

        element.addEventListener('mouseenter', () => {
            chars.forEach(char => {
                gsap.to(char, {
                    x: gsap.utils.random(-8, 8),
                    y: gsap.utils.random(-8, 8),
                    rotation: gsap.utils.random(-25, 25),
                    scale: gsap.utils.random(0.9, 1.15),
                    color: '#E8593C', // Accent highlight color on hover
                    duration: 0.3,
                    ease: 'power2.out',
                    overwrite: 'auto'
                });
            });
        });

        element.addEventListener('mouseleave', () => {
            chars.forEach(char => {
                gsap.to(char, {
                    x: 0,
                    y: 0,
                    rotation: 0,
                    scale: 1,
                    color: 'inherit',
                    duration: 0.6,
                    ease: 'elastic.out(1, 0.6)',
                    overwrite: 'auto'
                });
            });
        });
    });
});
