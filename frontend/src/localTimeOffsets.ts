function localFields(instant: Date, timeZone: string) {
    const parts = new Intl.DateTimeFormat('en-CA', {
        timeZone,
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        hourCycle: 'h23',
    }).formatToParts(instant);
    return Object.fromEntries(parts.map(({ type, value }) => [type, value]));
}

function offsetText(minutes: number) {
    const sign = minutes < 0 ? '-' : '+';
    const absolute = Math.abs(minutes);
    return `${sign}${String(Math.floor(absolute / 60)).padStart(2, '0')}:${String(absolute % 60).padStart(2, '0')}`;
}

export function validLocalTimeOffsets(
    date: string,
    time: string,
    timeZone: string,
) {
    if (!date || !time || !timeZone) return [];
    const [year, month, day] = date.split('-').map(Number);
    const [hour, minute] = time.split(':').map(Number);
    const localEpoch = Date.UTC(year, month - 1, day, hour, minute);
    const offsets = new Set<number>();
    for (let deltaMinutes = -1080; deltaMinutes <= 1080; deltaMinutes += 60) {
        const sample = new Date(localEpoch + deltaMinutes * 60000);
        const fields = localFields(sample, timeZone);
        const renderedEpoch = Date.UTC(
            Number(fields.year),
            Number(fields.month) - 1,
            Number(fields.day),
            Number(fields.hour),
            Number(fields.minute),
        );
        offsets.add((renderedEpoch - sample.getTime()) / 60000);
    }
    return [...offsets]
        .filter((offset) => {
            const candidate = new Date(localEpoch - offset * 60000);
            const fields = localFields(candidate, timeZone);
            return (
                Number(fields.year) === year &&
                Number(fields.month) === month &&
                Number(fields.day) === day &&
                Number(fields.hour) === hour &&
                Number(fields.minute) === minute
            );
        })
        .sort((left, right) => right - left)
        .map(offsetText);
}
